package com.company.booking.booking;

import com.company.booking.booking.BookingDtos.BookingResponse;
import com.company.booking.booking.BookingDtos.CancelSeriesResponse;
import com.company.booking.booking.BookingDtos.ConflictPolicy;
import com.company.booking.booking.BookingDtos.CreateBookingRequest;
import com.company.booking.booking.BookingDtos.CreateSeriesRequest;
import com.company.booking.booking.BookingDtos.Frequency;
import com.company.booking.booking.BookingDtos.SeriesResponse;
import com.company.booking.booking.BookingDtos.SkippedOccurrence;
import com.company.booking.booking.BookingDtos.UpdateBookingRequest;
import com.company.booking.common.ApiException;
import com.company.booking.common.AppProperties;
import com.company.booking.common.AuditLog;
import com.company.booking.common.PageResponse;
import com.company.booking.common.SortGuard;
import com.company.booking.room.Room;
import com.company.booking.room.RoomRepository;
import com.company.booking.security.AuthUser;
import com.company.booking.user.User;
import com.company.booking.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final String OVERLAP_CONSTRAINT = "bookings_no_overlap";
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final Set<String> SORTABLE = Set.of("startTime", "endTime", "createdAt", "status");
    /** "No booking to exclude" for the quota queries (a real id can never be all zeros). */
    private static final UUID NONE = new UUID(0, 0);

    private final BookingRepository bookings;
    private final RoomRepository rooms;
    private final UserRepository users;
    private final BookingRules rules;
    private final BookingMapper mapper;
    private final AppProperties props;
    private final Clock clock;
    private final MeterRegistry metrics;

    @Transactional
    public BookingResponse create(UUID userId, CreateBookingRequest req) {
        TimeWindow w = rules.validate(req.startTime(), req.endTime());
        User user = lockedActiveUser(userId);
        Room room = bookableRoom(req.roomId(), req.attendees());
        enforceQuotas(userId, List.of(w), NONE);

        // Friendly pre-check; the DB exclusion constraint below is what actually guarantees correctness.
        if (bookings.existsConflict(room.getId(), w.start(), w.end())) {
            throw conflict();
        }
        Booking b = newBooking(room, user, req.title(), req.description(), w, req.attendees(), null,
                Boolean.TRUE.equals(req.privateMeeting()));
        Booking saved = saveOrConflict(b, room);
        metrics.counter("booking.created", "kind", "single").increment();
        return mapper.toResponse(saved);
    }

    /**
     * Recurring booking. All occurrences are validated with the same rules as a single booking (but may reach
     * {@code maxSeriesDays} ahead); the user and room rows are locked once, so quotas and room closures cannot race,
     * and the exclusion constraint still guards every row.
     */
    @Transactional
    public SeriesResponse createSeries(UUID userId, CreateSeriesRequest req) {
        ZoneId zone = rules.zone();
        ZonedDateTime first = req.startTime().atZoneSameInstant(zone);
        ZonedDateTime firstEnd = req.endTime().atZoneSameInstant(zone);
        if (req.frequency() == Frequency.WEEKDAYS && isWeekend(first.toLocalDate())) {
            throw ApiException.unprocessable("START_ON_WEEKEND", "A weekday series must start on a Monday-Friday");
        }

        List<LocalDate> dates = occurrenceDates(first.toLocalDate(), req.frequency(), req.occurrences());
        List<TimeWindow> windows = new ArrayList<>();
        for (LocalDate d : dates) {
            OffsetDateTime s = ZonedDateTime.of(d, first.toLocalTime(), zone).toOffsetDateTime();
            OffsetDateTime e = ZonedDateTime.of(d, firstEnd.toLocalTime(), zone).toOffsetDateTime();
            try {
                windows.add(windows.isEmpty() ? rules.validate(s, e) : rules.validateOccurrence(s, e));
            } catch (ApiException ex) {
                throw new ApiException(ex.getStatus(), ex.getCode(), "Occurrence on " + d + ": " + ex.getMessage(), ex.getProperties());
            }
        }

        User user = lockedActiveUser(userId);
        Room room = bookableRoom(req.roomId(), req.attendees());

        List<LocalDate> clashing = new ArrayList<>();
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            if (bookings.existsConflict(room.getId(), windows.get(i).start(), windows.get(i).end())) {
                clashing.add(dates.get(i));
            } else {
                free.add(i);
            }
        }
        ConflictPolicy policy = req.onConflict() == null ? ConflictPolicy.FAIL : req.onConflict();
        if (!clashing.isEmpty() && (policy == ConflictPolicy.FAIL || free.isEmpty())) {
            throw ApiException.conflict("SERIES_CONFLICT",
                    clashing.size() + " of " + dates.size() + " occurrences clash with existing bookings")
                    .with("conflicts", clashing);
        }
        enforceQuotas(userId, free.stream().map(windows::get).toList(), NONE);

        UUID seriesId = UUID.randomUUID();
        List<Booking> toSave = new ArrayList<>();
        for (int i : free) {
            toSave.add(newBooking(room, user, req.title(), req.description(), windows.get(i), req.attendees(), seriesId,
                    Boolean.TRUE.equals(req.privateMeeting())));
        }
        List<Booking> saved;
        try {
            saved = bookings.saveAllAndFlush(toSave);
        } catch (RuntimeException e) {
            if (isOverlapViolation(e)) {
                // someone booked one of the slots between our check and the insert: nothing is kept
                throw ApiException.conflict("SERIES_CONFLICT", "A slot was taken while booking, please try again");
            }
            throw e;
        }
        metrics.counter("booking.created", "kind", "series").increment(saved.size());
        List<SkippedOccurrence> skipped = clashing.stream()
                .map(d -> new SkippedOccurrence(d, "ROOM_BOOKED")).toList();
        return new SeriesResponse(seriesId, saved.stream().map(mapper::toResponse).toList(), skipped);
    }

    /**
     * Edit a booking. Before it starts anything may change (including the room). Once it is running only the end can
     * move: extend a meeting that overruns, or end it early to give the room back.
     */
    @Transactional
    public BookingResponse update(AuthUser actor, UUID id, UpdateBookingRequest req) {
        Booking b = load(id);
        authorize(actor, b);
        Instant now = clock.instant();
        if (b.getKind() == BookingKind.BLOCK) {
            throw ApiException.unprocessable("BLOCK_NOT_EDITABLE", "A room block cannot be edited; cancel it and create a new one");
        }
        if (b.getStatus() != BookingStatus.CONFIRMED) {
            throw ApiException.conflict("BOOKING_NOT_ACTIVE", "A cancelled booking cannot be modified");
        }
        if (!b.getEndTime().isAfter(now)) {
            throw ApiException.unprocessable("BOOKING_ALREADY_ENDED", "A booking that has ended cannot be modified");
        }
        boolean ongoing = !b.getStartTime().isAfter(now);
        TimeWindow w = ongoing ? rules.validateOngoing(req.startTime(), req.endTime())
                : rules.validate(req.startTime(), req.endTime());

        UUID targetRoomId = req.roomId() != null ? req.roomId() : b.getRoom().getId();
        if (ongoing && (!w.start().equals(b.getStartTime()) || !targetRoomId.equals(b.getRoom().getId()))) {
            throw ApiException.unprocessable("BOOKING_ALREADY_STARTED",
                    "A meeting that has started can only have its end time changed");
        }
        lockedActiveUser(b.getUser().getId());
        Room room = bookableRoom(targetRoomId, req.attendees());
        if (bookings.existsConflictExcluding(room.getId(), w.start(), w.end(), b.getId())) {
            throw conflict();
        }
        enforceQuotas(b.getUser().getId(), List.of(w), b.getId());

        b.setRoom(room);
        b.setTitle(req.title().trim());
        b.setDescription(req.description());
        b.setAttendees(req.attendees());
        b.setPrivateBooking(Boolean.TRUE.equals(req.privateMeeting()));
        b.setStartTime(w.start());
        b.setEndTime(w.end());
        return mapper.toResponse(saveOrConflict(b, room));
    }

    @Transactional
    public BookingResponse cancel(AuthUser actor, UUID id, String reason) {
        Booking b = load(id);
        authorize(actor, b);
        Instant now = clock.instant();
        if (b.getStatus() == BookingStatus.CANCELLED) {
            throw ApiException.conflict("BOOKING_ALREADY_CANCELLED", "Booking is already cancelled");
        }
        if (!b.getEndTime().isAfter(now)) {
            throw ApiException.unprocessable("BOOKING_ALREADY_ENDED", "A booking that has ended cannot be cancelled");
        }
        markCancelled(b, now, actor.id(), reason);
        Booking saved = bookings.saveAndFlush(b);
        metrics.counter("booking.cancelled").increment();
        if (!b.getUser().getId().equals(actor.id())) {
            AuditLog.record("booking.cancel.by-admin", Map.of("booking", id, "owner", b.getUser().getId(), "room", b.getRoom().getName()));
        }
        return mapper.toResponse(saved);
    }

    /** Cancels this occurrence and every later, still-active one of the same series. */
    @Transactional
    public CancelSeriesResponse cancelSeries(AuthUser actor, UUID id, String reason) {
        Booking b = load(id);
        authorize(actor, b);
        if (b.getSeriesId() == null) {
            throw ApiException.unprocessable("NOT_A_SERIES", "This booking is not part of a recurring series");
        }
        Instant now = clock.instant();
        List<Booking> occurrences = bookings.findActiveSeriesFrom(b.getSeriesId(), b.getStartTime(), now);
        occurrences.forEach(o -> markCancelled(o, now, actor.id(), reason));
        bookings.saveAllAndFlush(occurrences);
        metrics.counter("booking.cancelled").increment(occurrences.size());
        if (!b.getUser().getId().equals(actor.id())) {
            AuditLog.record("booking.cancel-series.by-admin", Map.of("series", b.getSeriesId(), "owner", b.getUser().getId(), "count", occurrences.size()));
        }
        return new CancelSeriesResponse(occurrences.size());
    }

    @Transactional(readOnly = true)
    public BookingResponse get(AuthUser actor, UUID id) {
        Booking b = load(id);
        authorize(actor, b);
        return mapper.toResponse(b);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> search(
            UUID roomId, UUID userId, BookingStatus status, Instant from, Instant to, Instant endedBefore,
            BookingKind kind, Pageable pageable) {
        SortGuard.check(pageable, SORTABLE);
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "'to' must be after 'from'");
        }
        return PageResponse.from(bookings.findAll(
                BookingSpecifications.filter(roomId, userId, status, from, to, endedBefore, kind), pageable)
                .map(mapper::toResponse));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Row-locks the person (their JWT may outlive the account, so the database is the source of truth). Holding the
     * lock while quotas are checked makes the limits hard guarantees even when one person fires parallel requests.
     */
    private User lockedActiveUser(UUID userId) {
        return users.findByIdForUpdate(userId).filter(User::isActive)
                .orElseThrow(() -> ApiException.forbidden("ACCOUNT_DISABLED", "Your account is disabled"));
    }

    /** Share-locks the room (so a concurrent closure waits) and checks it is open and big enough. */
    private Room bookableRoom(UUID roomId, int attendees) {
        Room room = rooms.findByIdForShare(roomId)
                .orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "Room not found"));
        if (!room.isActive()) {
            throw ApiException.unprocessable("ROOM_INACTIVE", "This room is not available for booking");
        }
        if (attendees > room.getCapacity()) {
            throw ApiException.unprocessable("OVER_CAPACITY",
                    room.getName() + " seats " + room.getCapacity() + " but you entered " + attendees + " attendees");
        }
        return room;
    }

    /**
     * Two limits against one person hoarding rooms (a mistake, or a hijacked account):
     * how many upcoming bookings they hold, and how many hours they book on any single day.
     * {@code excludeId} is the booking being edited, so it is not counted twice.
     */
    private void enforceQuotas(UUID ownerId, List<TimeWindow> adding, UUID excludeId) {
        AppProperties.Rules cfg = props.rules();
        Instant now = clock.instant();
        // the window ends with the last bookable calendar day, the same boundary the validation rules use
        Instant windowEnd = rules.lastBookableDay(cfg.maxAdvanceDays()).plusDays(1).atStartOfDay(rules.zone()).toInstant();

        long newInWindow = adding.stream().filter(w -> w.start().isBefore(windowEnd)).count();
        long held = bookings.countActiveInWindow(ownerId, now, windowEnd, excludeId);
        if (held + newInWindow > cfg.maxActiveBookingsPerUser()) {
            reject("quota.active");
            throw ApiException.unprocessable("BOOKING_LIMIT_REACHED",
                    "You can hold at most " + cfg.maxActiveBookingsPerUser() + " upcoming bookings in the next "
                            + cfg.maxAdvanceDays() + " days (you have " + held + ")")
                    .with("limit", cfg.maxActiveBookingsPerUser()).with("days", cfg.maxAdvanceDays());
        }

        ZoneId zone = rules.zone();
        Map<LocalDate, Long> perDay = new TreeMap<>();
        for (TimeWindow w : adding) {
            perDay.merge(w.start().atZone(zone).toLocalDate(), Duration.between(w.start(), w.end()).toMinutes(), Long::sum);
        }
        for (Map.Entry<LocalDate, Long> day : perDay.entrySet()) {
            Instant from = day.getKey().atStartOfDay(zone).toInstant();
            Instant to = day.getKey().plusDays(1).atStartOfDay(zone).toInstant();
            double booked = bookings.sumBookedMinutes(ownerId, from, to, excludeId);
            if (booked + day.getValue() > cfg.maxBookedMinutesPerDay()) {
                reject("quota.daily");
                int limit = cfg.maxBookedMinutesPerDay();
                throw ApiException.unprocessable("DAILY_LIMIT_REACHED",
                        "You can book at most " + hours(limit) + " hours per day; this would exceed it on " + day.getKey())
                        .with("date", day.getKey().toString()).with("limitHours", hours(limit));
            }
        }
    }

    private static String hours(int minutes) {
        return minutes % 60 == 0 ? String.valueOf(minutes / 60) : String.format("%.1f", minutes / 60.0);
    }

    private void reject(String reason) {
        metrics.counter("booking.rejected", "reason", reason).increment();
    }

    private static Booking newBooking(Room room, User user, String title, String description, TimeWindow w,
                                      int attendees, UUID seriesId, boolean privateBooking) {
        Booking b = new Booking();
        b.setRoom(room);
        b.setUser(user);
        b.setTitle(title.trim());
        b.setDescription(description);
        b.setAttendees(attendees);
        b.setSeriesId(seriesId);
        b.setPrivateBooking(privateBooking);
        b.setStartTime(w.start());
        b.setEndTime(w.end());
        return b;
    }

    static void markCancelled(Booking b, Instant now, UUID by, String reason) {
        b.setStatus(BookingStatus.CANCELLED);
        b.setCancelledAt(now);
        b.setCancelledBy(by);
        b.setCancelReason(reason == null || reason.isBlank() ? null : reason.trim());
    }

    static List<LocalDate> occurrenceDates(LocalDate start, Frequency f, int count) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = start;
        while (out.size() < count) {
            if (f != Frequency.WEEKDAYS || !isWeekend(d)) {
                out.add(d);
            }
            d = switch (f) {
                case WEEKLY -> d.plusWeeks(1);
                case BIWEEKLY -> d.plusWeeks(2);
                case WEEKDAYS -> d.plusDays(1);
            };
        }
        return out;
    }

    private static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private Booking load(UUID id) {
        return bookings.findWithDetailsById(id)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
    }

    private static void authorize(AuthUser actor, Booking b) {
        boolean owner = b.getUser().getId().equals(actor.id());
        // Blocks are administrative: only admins act on them, whoever created them.
        if (!actor.isAdmin() && (!owner || b.getKind() == BookingKind.BLOCK)) {
            throw ApiException.forbidden("NOT_BOOKING_OWNER", "You can only access your own bookings");
        }
    }

    Booking saveOrConflict(Booking b, Room room) {
        try {
            return bookings.saveAndFlush(b);
        } catch (RuntimeException e) {
            if (isOverlapViolation(e)) {
                log.info("Overlap rejected by database constraint for room {}", room.getId());
                metrics.counter("booking.rejected", "reason", "conflict").increment();
                throw conflict();
            }
            throw e;
        }
    }

    /** Walks the cause chain: the PG error is wrapped by Hibernate and Spring, whose type varies by version. */
    static boolean isOverlapViolation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
            if (c.getMessage() != null && c.getMessage().contains(OVERLAP_CONSTRAINT)) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private ApiException conflict() {
        metrics.counter("booking.rejected", "reason", "conflict").increment();
        return ApiException.conflict("BOOKING_CONFLICT", "The room is already booked for the requested time");
    }
}

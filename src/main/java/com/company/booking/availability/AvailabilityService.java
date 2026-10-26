package com.company.booking.availability;

import com.company.booking.availability.AvailabilityDtos.DayScheduleResponse;
import com.company.booking.availability.AvailabilityDtos.FreeSlot;
import com.company.booking.availability.AvailabilityDtos.RoomScheduleResponse;
import com.company.booking.booking.Booking;
import com.company.booking.booking.BookingDtos.BookingResponse;
import com.company.booking.booking.BookingMapper;
import com.company.booking.booking.BookingRepository;
import com.company.booking.booking.BookingRules;
import com.company.booking.booking.TimeWindow;
import com.company.booking.common.ApiException;
import com.company.booking.common.AppProperties;
import com.company.booking.common.PageResponse;
import com.company.booking.common.SortGuard;
import com.company.booking.room.Room;
import com.company.booking.room.RoomDtos.RoomRef;
import com.company.booking.room.RoomDtos.RoomResponse;
import com.company.booking.room.RoomRepository;
import com.company.booking.room.RoomService;
import com.company.booking.room.RoomSpecifications;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AvailabilityService {

    private static final int MAX_SCHEDULE_DAYS = 31;
    private static final Set<String> SORTABLE = Set.of("name", "capacity", "location");

    private final RoomRepository rooms;
    private final BookingRepository bookings;
    private final BookingRules rules;
    private final BookingMapper mapper;
    private final AppProperties props;
    private final Clock clock;

    /** Active rooms free for the whole [start, end) window; same time rules as creating a booking. */
    public PageResponse<RoomResponse> availableRooms(
            OffsetDateTime start, OffsetDateTime end, Integer minCapacity, Set<String> equipment, Pageable pageable) {
        SortGuard.check(pageable, SORTABLE);
        TimeWindow w = rules.validate(start, end);
        return PageResponse.from(rooms.findAll(
                RoomSpecifications.availableBetween(w.start(), w.end(), minCapacity, RoomService.normalizeEquipment(equipment)),
                pageable).map(RoomResponse::from));
    }

    public RoomScheduleResponse roomSchedule(UUID roomId, LocalDate date, OffsetDateTime from, OffsetDateTime to) {
        Room room = rooms.findById(roomId).orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "Room not found"));
        Instant f;
        Instant t;
        if (date != null) {
            f = startOfDay(date);
            t = startOfDay(date.plusDays(1));
        } else if (from != null && to != null) {
            f = from.toInstant();
            t = to.toInstant();
            if (!t.isAfter(f)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "'to' must be after 'from'");
            }
            if (Duration.between(f, t).toDays() > MAX_SCHEDULE_DAYS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RANGE_TOO_LARGE",
                        "Range must not exceed " + MAX_SCHEDULE_DAYS + " days");
            }
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_RANGE", "Provide either 'date' or both 'from' and 'to'");
        }
        List<BookingResponse> items = bookings.findRoomSchedule(roomId, f, t).stream().map(mapper::toResponse).toList();
        return new RoomScheduleResponse(RoomRef.from(room), mapper.local(f), mapper.local(t), items);
    }

    /** Every active room with its bookings for the day (rooms with no bookings are included). */
    public DayScheduleResponse daySchedule(LocalDate date) {
        Instant f = startOfDay(date);
        Instant t = startOfDay(date.plusDays(1));
        Map<UUID, List<BookingResponse>> byRoom = new LinkedHashMap<>();
        for (Booking b : bookings.findSchedule(f, t)) {
            byRoom.computeIfAbsent(b.getRoom().getId(), k -> new ArrayList<>()).add(mapper.toResponse(b));
        }
        List<RoomScheduleResponse> result = rooms.findAll(
                        RoomSpecifications.filter(null, null, true), org.springframework.data.domain.Sort.by("name"))
                .stream()
                .map(r -> new RoomScheduleResponse(RoomRef.from(r), mapper.local(f), mapper.local(t),
                        byRoom.getOrDefault(r.getId(), List.of())))
                .toList();
        return new DayScheduleResponse(date, result);
    }

    /** Gaps inside working hours of {@code date} that are at least {@code minMinutes} long. */
    public List<FreeSlot> freeSlots(UUID roomId, LocalDate date, Integer minMinutes) {
        Room room = rooms.findById(roomId).orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "Room not found"));
        if (!room.isActive()) {
            throw ApiException.unprocessable("ROOM_INACTIVE", "This room is not available for booking");
        }
        AppProperties.Rules cfg = props.rules();
        int min = minMinutes == null ? cfg.minDurationMinutes() : minMinutes;
        if (min < 1 || min > cfg.maxDurationMinutes()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DURATION",
                    "minDuration must be between 1 and " + cfg.maxDurationMinutes() + " minutes");
        }
        ZoneId zone = cfg.timezone();
        Instant dayStart = date.atTime(cfg.workdayStart()).atZone(zone).toInstant();
        Instant dayEnd = date.atTime(cfg.workdayEnd()).atZone(zone).toInstant();
        Instant lower = max(dayStart, ceilToSlot(clock.instant(), zone, cfg.slotMinutes()));
        if (!lower.isBefore(dayEnd)) {
            return List.of(); // past day, or the working day is already over
        }
        List<FreeSlot> slots = new ArrayList<>();
        Instant cursor = lower;
        for (Booking b : bookings.findRoomSchedule(roomId, lower, dayEnd)) {
            if (b.getStartTime().isAfter(cursor)) {
                addIfLongEnough(slots, cursor, b.getStartTime(), min);
            }
            cursor = max(cursor, b.getEndTime());
        }
        if (dayEnd.isAfter(cursor)) {
            addIfLongEnough(slots, cursor, dayEnd, min);
        }
        return slots;
    }

    private void addIfLongEnough(List<FreeSlot> out, Instant s, Instant e, int min) {
        long minutes = Duration.between(s, e).toMinutes();
        if (minutes >= min) {
            out.add(new FreeSlot(mapper.local(s), mapper.local(e), minutes));
        }
    }

    private Instant startOfDay(LocalDate d) {
        return d.atStartOfDay(rules.zone()).toInstant();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    /** Next slot boundary at or after {@code now}, e.g. 10:07 -> 10:15 for 15-minute slots. */
    static Instant ceilToSlot(Instant now, ZoneId zone, int slotMinutes) {
        ZonedDateTime z = now.atZone(zone);
        ZonedDateTime midnight = z.truncatedTo(ChronoUnit.DAYS);
        long secs = Duration.between(midnight, z).getSeconds() + (z.getNano() > 0 ? 1 : 0);
        long slot = slotMinutes * 60L;
        long ceil = ((secs + slot - 1) / slot) * slot;
        return midnight.plusSeconds(ceil).toInstant();
    }
}

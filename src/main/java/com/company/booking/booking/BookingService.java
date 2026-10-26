package com.company.booking.booking;

import com.company.booking.booking.BookingDtos.BookingResponse;
import com.company.booking.booking.BookingDtos.CreateBookingRequest;
import com.company.booking.booking.BookingDtos.UpdateBookingRequest;
import com.company.booking.common.ApiException;
import com.company.booking.common.AppProperties;
import com.company.booking.common.PageResponse;
import com.company.booking.common.SortGuard;
import com.company.booking.room.Room;
import com.company.booking.room.RoomRepository;
import com.company.booking.security.AuthUser;
import com.company.booking.user.User;
import com.company.booking.user.UserRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final String OVERLAP_CONSTRAINT = "bookings_no_overlap";
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final Set<String> SORTABLE = Set.of("startTime", "endTime", "createdAt", "status");

    private final BookingRepository bookings;
    private final RoomRepository rooms;
    private final UserRepository users;
    private final BookingRules rules;
    private final BookingMapper mapper;
    private final AppProperties props;
    private final Clock clock;

    @Transactional
    public BookingResponse create(UUID userId, CreateBookingRequest req) {
        TimeWindow w = rules.validate(req.startTime(), req.endTime());

        // The JWT may outlive the account, so check the source of truth.
        User user = users.findById(userId).filter(User::isActive)
                .orElseThrow(() -> ApiException.forbidden("ACCOUNT_DISABLED", "Your account is disabled"));

        Room room = rooms.findByIdForShare(req.roomId())
                .orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "Room not found"));
        if (!room.isActive()) {
            throw ApiException.unprocessable("ROOM_INACTIVE", "This room is not available for booking");
        }

        // Soft limit against hoarding rooms (not strictly race-free; the hard guarantee is only for overlaps).
        int max = props.rules().maxActiveBookingsPerUser();
        if (bookings.countActiveByUser(userId, clock.instant()) >= max) {
            throw ApiException.unprocessable("BOOKING_LIMIT_REACHED",
                    "You already have " + max + " upcoming bookings; cancel one first");
        }

        // Friendly pre-check; the DB exclusion constraint below is what actually guarantees correctness.
        if (bookings.existsConflict(room.getId(), w.start(), w.end())) {
            throw conflict();
        }

        Booking b = new Booking();
        b.setRoom(room);
        b.setUser(user);
        b.setTitle(req.title().trim());
        b.setDescription(req.description());
        b.setStartTime(w.start());
        b.setEndTime(w.end());
        return mapper.toResponse(saveOrConflict(b));
    }

    @Transactional
    public BookingResponse update(AuthUser actor, UUID id, UpdateBookingRequest req) {
        Booking b = load(id);
        authorize(actor, b);
        Instant now = clock.instant();
        if (b.getStatus() != BookingStatus.CONFIRMED) {
            throw ApiException.conflict("BOOKING_NOT_ACTIVE", "A cancelled booking cannot be modified");
        }
        if (!b.getStartTime().isAfter(now)) {
            throw ApiException.unprocessable("BOOKING_ALREADY_STARTED", "A booking that has started cannot be modified");
        }
        TimeWindow w = rules.validate(req.startTime(), req.endTime());

        Room room = rooms.findByIdForShare(b.getRoom().getId())
                .orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "Room not found"));
        if (!room.isActive()) {
            throw ApiException.unprocessable("ROOM_INACTIVE", "This room is not available for booking");
        }
        if (bookings.existsConflictExcluding(room.getId(), w.start(), w.end(), b.getId())) {
            throw conflict();
        }
        b.setTitle(req.title().trim());
        b.setDescription(req.description());
        b.setStartTime(w.start());
        b.setEndTime(w.end());
        return mapper.toResponse(saveOrConflict(b));
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
        b.setStatus(BookingStatus.CANCELLED);
        b.setCancelledAt(now);
        b.setCancelledBy(actor.id());
        b.setCancelReason(reason == null || reason.isBlank() ? null : reason.trim());
        return mapper.toResponse(bookings.saveAndFlush(b));
    }

    @Transactional(readOnly = true)
    public BookingResponse get(AuthUser actor, UUID id) {
        Booking b = load(id);
        authorize(actor, b);
        return mapper.toResponse(b);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> search(
            UUID roomId, UUID userId, BookingStatus status, Instant from, Instant to, Pageable pageable) {
        SortGuard.check(pageable, SORTABLE);
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_RANGE", "'to' must be after 'from'");
        }
        return PageResponse.from(bookings.findAll(
                BookingSpecifications.filter(roomId, userId, status, from, to), pageable).map(mapper::toResponse));
    }

    private Booking load(UUID id) {
        return bookings.findWithDetailsById(id)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
    }

    private static void authorize(AuthUser actor, Booking b) {
        if (!actor.isAdmin() && !b.getUser().getId().equals(actor.id())) {
            throw ApiException.forbidden("NOT_BOOKING_OWNER", "You can only access your own bookings");
        }
    }

    private Booking saveOrConflict(Booking b) {
        try {
            return bookings.saveAndFlush(b);
        } catch (RuntimeException e) {
            if (isOverlapViolation(e)) {
                log.info("Overlap rejected by database constraint for room {}", b.getRoom().getId());
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

    private static ApiException conflict() {
        return ApiException.conflict("BOOKING_CONFLICT", "The room is already booked for the requested time");
    }
}

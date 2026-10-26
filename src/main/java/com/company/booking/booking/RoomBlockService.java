package com.company.booking.booking;

import com.company.booking.booking.BookingDtos.BlockResult;
import com.company.booking.booking.BookingDtos.CreateBlockRequest;
import com.company.booking.common.ApiException;
import com.company.booking.room.Room;
import com.company.booking.room.RoomRepository;
import com.company.booking.security.AuthUser;
import com.company.booking.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrative holds on a room. They live in the bookings table (kind = BLOCK) so the same exclusion
 * constraint keeps blocks and bookings from ever overlapping, even under concurrency.
 */
@Service
@RequiredArgsConstructor
public class RoomBlockService {

    private final BookingRepository bookings;
    private final RoomRepository rooms;
    private final UserRepository users;
    private final BookingRules rules;
    private final BookingMapper mapper;
    private final BookingService bookingService;
    private final Clock clock;

    @Transactional
    public BlockResult create(AuthUser admin, CreateBlockRequest req) {
        TimeWindow w = rules.validateBlock(req.startTime(), req.endTime());
        // FOR UPDATE: a block changes what the room can offer, so concurrent bookings wait for the outcome.
        Room room = rooms.findByIdForUpdate(req.roomId())
                .orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "Room not found"));

        List<Booking> overlapping = bookings.findConfirmedOverlapping(room.getId(), w.start(), w.end());
        if (overlapping.stream().anyMatch(o -> o.getKind() == BookingKind.BLOCK)) {
            throw ApiException.conflict("ALREADY_BLOCKED", "Part of this period is already blocked for " + room.getName());
        }

        Instant now = clock.instant();
        if (!overlapping.isEmpty() && !req.cancelConflicting()) {
            List<Map<String, Object>> list = overlapping.stream().map(o -> Map.<String, Object>of(
                    "id", o.getId(), "title", o.getTitle(), "booker", o.getUser().getFullName(),
                    "startTime", mapper.local(o.getStartTime()), "endTime", mapper.local(o.getEndTime()))).toList();
            throw ApiException.conflict("BLOCK_CONFLICTS_WITH_BOOKINGS",
                    overlapping.size() + " existing booking(s) overlap this period")
                    .with("conflicts", list);
        }
        String reason = req.reason().trim();
        for (Booking o : overlapping) {
            BookingService.markCancelled(o, now, admin.id(), "Room unavailable: " + reason);
        }
        // Cancelled rows must reach the database before the block is inserted, or the constraint would still see them.
        bookings.saveAllAndFlush(overlapping);

        Booking block = new Booking();
        block.setKind(BookingKind.BLOCK);
        block.setRoom(room);
        block.setUser(users.getReferenceById(admin.id()));
        block.setTitle(reason);
        block.setStartTime(w.start());
        block.setEndTime(w.end());
        Booking saved = bookingService.saveOrConflict(block, room);
        return new BlockResult(mapper.toResponse(saved), overlapping.stream().map(mapper::toResponse).toList());
    }
}

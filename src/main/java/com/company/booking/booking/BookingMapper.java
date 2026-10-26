package com.company.booking.booking;

import com.company.booking.booking.BookingDtos.BookerRef;
import com.company.booking.booking.BookingDtos.BookingResponse;
import com.company.booking.common.AppProperties;
import com.company.booking.room.RoomDtos.RoomRef;
import com.company.booking.security.AuthUser;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Renders instants in the company time zone so API consumers see local wall-clock times with an offset. */
@Component
public class BookingMapper {

    private final ZoneId zone;

    public BookingMapper(AppProperties props) {
        this.zone = props.rules().timezone();
    }

    public OffsetDateTime local(Instant i) {
        return i == null ? null : i.atZone(zone).toOffsetDateTime();
    }

    /** Title shown to people who may not see a private booking (clients localise it using {@code masked}). */
    public static final String MASKED_TITLE = "Private";

    /**
     * Masking is decided here, once, for every endpoint that returns bookings. The viewer comes from the
     * security context; when there is none the safe answer is to mask.
     */
    private boolean isMasked(Booking b) {
        if (!b.isPrivateBooking() || b.getKind() != BookingKind.BOOKING) {
            return false;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser viewer) {
            return !(viewer.isAdmin() || viewer.id().equals(b.getUser().getId()));
        }
        return true;
    }

    /** True when somebody other than the owner (an administrator) cancelled it: the owner should be told. */
    private static boolean cancelledByAnother(Booking b) {
        return b.getStatus() == BookingStatus.CANCELLED && b.getCancelledBy() != null
                && !b.getCancelledBy().equals(b.getUser().getId());
    }

    /** Must be called inside a transaction: touches lazy room/user associations. */
    public BookingResponse toResponse(Booking b) {
        if (isMasked(b)) {
            return new BookingResponse(b.getId(), b.getKind(), RoomRef.from(b.getRoom()), null, MASKED_TITLE, null,
                    null, null, true, true, local(b.getStartTime()), local(b.getEndTime()), b.getStatus(),
                    null, null, false, null, null);
        }
        return new BookingResponse(
                b.getId(), b.getKind(), RoomRef.from(b.getRoom()),
                new BookerRef(b.getUser().getId(), b.getUser().getFullName()),
                b.getTitle(), b.getDescription(), b.getAttendees(), b.getSeriesId(), b.isPrivateBooking(), false,
                local(b.getStartTime()), local(b.getEndTime()),
                b.getStatus(), local(b.getCancelledAt()), b.getCancelReason(), cancelledByAnother(b),
                local(b.getCreatedAt()), local(b.getUpdatedAt()));
    }
}

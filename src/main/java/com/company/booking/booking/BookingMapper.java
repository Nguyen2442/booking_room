package com.company.booking.booking;

import com.company.booking.booking.BookingDtos.BookerRef;
import com.company.booking.booking.BookingDtos.BookingResponse;
import com.company.booking.common.AppProperties;
import com.company.booking.room.RoomDtos.RoomRef;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

    /** Must be called inside a transaction: touches lazy room/user associations. */
    public BookingResponse toResponse(Booking b) {
        return new BookingResponse(
                b.getId(), b.getKind(), RoomRef.from(b.getRoom()),
                new BookerRef(b.getUser().getId(), b.getUser().getFullName()),
                b.getTitle(), b.getDescription(), b.getAttendees(), b.getSeriesId(),
                local(b.getStartTime()), local(b.getEndTime()),
                b.getStatus(), local(b.getCancelledAt()), b.getCancelReason(),
                local(b.getCreatedAt()), local(b.getUpdatedAt()));
    }
}

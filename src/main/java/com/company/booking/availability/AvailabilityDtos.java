package com.company.booking.availability;

import com.company.booking.booking.BookingDtos.BookingResponse;
import com.company.booking.room.RoomDtos.RoomRef;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class AvailabilityDtos {

    private AvailabilityDtos() {}

    public record FreeSlot(OffsetDateTime start, OffsetDateTime end, long minutes) {}

    public record RoomScheduleResponse(RoomRef room, OffsetDateTime from, OffsetDateTime to, List<BookingResponse> bookings) {}

    public record DayScheduleResponse(LocalDate date, List<RoomScheduleResponse> rooms) {}
}

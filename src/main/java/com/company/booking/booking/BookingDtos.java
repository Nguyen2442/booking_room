package com.company.booking.booking;

import com.company.booking.room.RoomDtos.RoomRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class BookingDtos {

    private BookingDtos() {}

    public record CreateBookingRequest(
            @NotNull UUID roomId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotNull OffsetDateTime startTime,
            @NotNull OffsetDateTime endTime) {}

    public record UpdateBookingRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotNull OffsetDateTime startTime,
            @NotNull OffsetDateTime endTime) {}

    public record CancelBookingRequest(@Size(max = 500) String reason) {}

    /** Deliberately no email: schedules are visible to every employee. */
    public record BookerRef(UUID id, String fullName) {}

    public record BookingResponse(
            UUID id, RoomRef room, BookerRef booker, String title, String description,
            OffsetDateTime startTime, OffsetDateTime endTime, BookingStatus status,
            OffsetDateTime cancelledAt, String cancelReason, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}

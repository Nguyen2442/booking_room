package com.company.booking.booking;

import com.company.booking.room.RoomDtos.RoomRef;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class BookingDtos {

    private BookingDtos() {}

    public record CreateBookingRequest(
            @NotNull UUID roomId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotNull OffsetDateTime startTime,
            @NotNull OffsetDateTime endTime,
            @NotNull @Min(1) @Max(1000) Integer attendees) {}

    /** {@code roomId} is optional: send a different room to move the booking. */
    public record UpdateBookingRequest(
            UUID roomId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotNull OffsetDateTime startTime,
            @NotNull OffsetDateTime endTime,
            @NotNull @Min(1) @Max(1000) Integer attendees) {}

    public record CancelBookingRequest(@Size(max = 500) String reason) {}

    public enum Frequency {
        /** Same weekday every week. */
        WEEKLY,
        /** Same weekday every second week. */
        BIWEEKLY,
        /** Every Monday-Friday starting at the given date (which must itself be a weekday). */
        WEEKDAYS
    }

    public enum ConflictPolicy {
        /** Reject the whole series if any occurrence clashes (default). */
        FAIL,
        /** Create the free occurrences and report the clashing ones as skipped. */
        SKIP
    }

    public record CreateSeriesRequest(
            @NotNull UUID roomId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotNull OffsetDateTime startTime,
            @NotNull OffsetDateTime endTime,
            @NotNull @Min(1) @Max(1000) Integer attendees,
            @NotNull Frequency frequency,
            @NotNull @Min(2) @Max(26) Integer occurrences,
            ConflictPolicy onConflict) {}

    public record SkippedOccurrence(LocalDate date, String reason) {}

    public record SeriesResponse(UUID seriesId, List<BookingResponse> created, List<SkippedOccurrence> skipped) {}

    public record CancelSeriesResponse(int cancelled) {}

    public record CreateBlockRequest(
            @NotNull UUID roomId,
            @NotNull OffsetDateTime startTime,
            @NotNull OffsetDateTime endTime,
            @NotBlank @Size(max = 200) String reason,
            boolean cancelConflicting) {}

    public record BlockResult(BookingResponse block, List<BookingResponse> cancelledBookings) {}

    /** Deliberately no email: schedules are visible to every employee. */
    public record BookerRef(UUID id, String fullName) {}

    public record BookingResponse(
            UUID id, BookingKind kind, RoomRef room, BookerRef booker, String title, String description,
            Integer attendees, UUID seriesId,
            OffsetDateTime startTime, OffsetDateTime endTime, BookingStatus status,
            OffsetDateTime cancelledAt, String cancelReason, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}

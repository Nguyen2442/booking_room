package com.company.booking.booking;

import com.company.booking.booking.BookingDtos.BookingResponse;
import com.company.booking.booking.BookingDtos.CancelBookingRequest;
import com.company.booking.booking.BookingDtos.CancelSeriesResponse;
import com.company.booking.booking.BookingDtos.CreateSeriesRequest;
import com.company.booking.booking.BookingDtos.SeriesResponse;
import com.company.booking.booking.BookingDtos.CreateBookingRequest;
import com.company.booking.booking.BookingDtos.UpdateBookingRequest;
import com.company.booking.common.PageResponse;
import com.company.booking.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Tag(name = "Bookings")
public class BookingController {

    private final BookingService service;

    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateBookingRequest req) {
        BookingResponse created = service.create(user.id(), req);
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + created.id())).body(created);
    }

    @GetMapping("/my")
    public PageResponse<BookingResponse> mine(
            @AuthenticationPrincipal AuthUser user,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) OffsetDateTime endedBefore,
            @PageableDefault(sort = "startTime", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.search(null, user.id(), status, instant(from), instant(to), instant(endedBefore), BookingKind.BOOKING, pageable);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<BookingResponse> all(
            @RequestParam(required = false) UUID roomId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) OffsetDateTime endedBefore,
            @RequestParam(required = false) BookingKind kind,
            @PageableDefault(sort = "startTime", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.search(roomId, userId, status, instant(from), instant(to), instant(endedBefore), kind, pageable);
    }

    @PostMapping("/series")
    public ResponseEntity<SeriesResponse> createSeries(
            @AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateSeriesRequest req) {
        return ResponseEntity.status(201).body(service.createSeries(user.id(), req));
    }

    @PostMapping("/{id}/cancel-series")
    public CancelSeriesResponse cancelSeries(
            @AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @Valid @RequestBody(required = false) CancelBookingRequest req) {
        return service.cancelSeries(user, id, req == null ? null : req.reason());
    }

    @GetMapping("/{id}")
    public BookingResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return service.get(user, id);
    }

    @PutMapping("/{id}")
    public BookingResponse update(
            @AuthenticationPrincipal AuthUser user, @PathVariable UUID id, @Valid @RequestBody UpdateBookingRequest req) {
        return service.update(user, id, req);
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(
            @AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @Valid @RequestBody(required = false) CancelBookingRequest req) {
        return service.cancel(user, id, req == null ? null : req.reason());
    }

    private static java.time.Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}

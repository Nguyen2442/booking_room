package com.company.booking.booking;

import com.company.booking.booking.BookingDtos.BlockResult;
import com.company.booking.booking.BookingDtos.CreateBlockRequest;
import com.company.booking.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create a block here; list them with GET /bookings?kind=BLOCK and lift one with POST /bookings/{id}/cancel.
 */
@RestController
@RequestMapping("/api/v1/admin/blocks")
@RequiredArgsConstructor
@Tag(name = "Admin - Room blocks")
public class AdminBlockController {

    private final RoomBlockService service;

    @PostMapping
    public ResponseEntity<BlockResult> create(@AuthenticationPrincipal AuthUser admin, @Valid @RequestBody CreateBlockRequest req) {
        BlockResult result = service.create(admin, req);
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + result.block().id())).body(result);
    }
}

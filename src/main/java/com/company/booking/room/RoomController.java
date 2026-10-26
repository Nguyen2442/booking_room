package com.company.booking.room;

import com.company.booking.common.PageResponse;
import com.company.booking.room.RoomDtos.RoomRequest;
import com.company.booking.room.RoomDtos.RoomResponse;
import com.company.booking.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
@Tag(name = "Rooms")
public class RoomController {

    private final RoomService service;

    @GetMapping
    public PageResponse<RoomResponse> list(
            @AuthenticationPrincipal AuthUser user,
            @RequestParam(required = false) @Min(1) Integer minCapacity,
            @RequestParam(required = false) Set<String> equipment,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(sort = "name") Pageable pageable) {
        return service.list(minCapacity, equipment, active, user.isAdmin(), pageable);
    }

    @GetMapping("/{id}")
    public RoomResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return service.get(id, user.isAdmin());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoomResponse> create(@Valid @RequestBody RoomRequest req) {
        RoomResponse created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v1/rooms/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public RoomResponse update(@PathVariable UUID id, @Valid @RequestBody RoomRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public RoomResponse activate(@PathVariable UUID id) {
        return service.activate(id);
    }
}

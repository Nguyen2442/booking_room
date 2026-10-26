package com.company.booking.room;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RoomDtos {

    private RoomDtos() {}

    public record RoomRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 200) String location,
            @NotNull @Min(1) @Max(1000) Integer capacity,
            @Size(max = 30) Set<@NotBlank @Size(max = 50) String> equipment,
            @Size(max = 1000) String description) {}

    public record RoomResponse(
            UUID id, String name, String location, int capacity, List<String> equipment, String description,
            boolean active, Instant createdAt, Instant updatedAt) {

        public static RoomResponse from(Room r) {
            return new RoomResponse(r.getId(), r.getName(), r.getLocation(), r.getCapacity(),
                    r.getEquipment().stream().sorted().toList(), r.getDescription(), r.isActive(),
                    r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    /** Minimal room reference embedded in booking/schedule payloads. */
    public record RoomRef(UUID id, String name) {
        public static RoomRef from(Room r) {
            return new RoomRef(r.getId(), r.getName());
        }
    }
}

package com.company.booking.availability;

import com.company.booking.availability.AvailabilityDtos.DayScheduleResponse;
import com.company.booking.availability.AvailabilityDtos.FreeSlot;
import com.company.booking.availability.AvailabilityDtos.RoomScheduleResponse;
import com.company.booking.common.PageResponse;
import com.company.booking.room.RoomDtos.RoomResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Schedule & availability")
public class AvailabilityController {

    private final AvailabilityService service;

    @GetMapping("/rooms/available")
    public PageResponse<RoomResponse> available(
            @RequestParam OffsetDateTime start,
            @RequestParam OffsetDateTime end,
            @RequestParam(required = false) @Min(1) Integer minCapacity,
            @RequestParam(required = false) Set<String> equipment,
            @PageableDefault(sort = {"capacity", "name"}) Pageable pageable) {
        return service.availableRooms(start, end, minCapacity, equipment, pageable);
    }

    @GetMapping("/rooms/{id}/schedule")
    public RoomScheduleResponse roomSchedule(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to) {
        return service.roomSchedule(id, date, from, to);
    }

    @GetMapping("/rooms/{id}/free-slots")
    public List<FreeSlot> freeSlots(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @Min(1) Integer minDuration) {
        return service.freeSlots(id, date, minDuration);
    }

    @GetMapping("/schedule")
    public DayScheduleResponse daySchedule(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.daySchedule(date);
    }
}

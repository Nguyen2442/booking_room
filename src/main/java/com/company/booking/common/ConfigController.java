package com.company.booking.common;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Booking rules for clients, so UIs never hard-code working hours, slot size or limits. */
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
@Tag(name = "Config")
public class ConfigController {

    public record PublicConfig(
            String timezone, String workdayStart, String workdayEnd, int slotMinutes,
            int minDurationMinutes, int maxDurationMinutes, int maxAdvanceDays) {}

    private final AppProperties props;

    @GetMapping
    public PublicConfig config() {
        AppProperties.Rules r = props.rules();
        return new PublicConfig(r.timezone().getId(), r.workdayStart().toString(), r.workdayEnd().toString(),
                r.slotMinutes(), r.minDurationMinutes(), r.maxDurationMinutes(), r.maxAdvanceDays());
    }
}

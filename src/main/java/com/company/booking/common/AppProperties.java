package com.company.booking.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Typed, validated configuration. Invalid configuration fails fast at startup. */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Rules rules,
        @NotNull Bootstrap bootstrap,
        @Valid @NotNull LoginProtection loginProtection,
        @Valid @NotNull RateLimit rateLimit,
        Cors cors) {

    public record Jwt(@NotBlank String secret, @NotNull Duration ttl, @NotBlank String issuer) {
        @AssertTrue(message = "app.jwt.secret must be at least 32 bytes (256 bits)")
        public boolean isSecretLongEnough() {
            return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
        }
    }

    public record Rules(
            @NotNull ZoneId timezone,
            @NotNull LocalTime workdayStart,
            @NotNull LocalTime workdayEnd,
            @Min(1) int slotMinutes,
            @Min(1) int minDurationMinutes,
            @Min(1) int maxDurationMinutes,
            /** How far ahead a single booking may start. */
            @Min(1) int maxAdvanceDays,
            /** Recurring bookings and room blocks may reach further than a single booking. */
            @Min(1) int maxSeriesDays,
            /** Upcoming bookings one person may hold that start within {@code maxAdvanceDays}. */
            @Min(1) int maxActiveBookingsPerUser,
            /** Total booked time per person per calendar day (company time zone), across all rooms. */
            @Min(15) int maxBookedMinutesPerDay,
            /** A booking may start this long before "now" so that the current slot can still be booked. */
            @Min(0) int startGraceMinutes) {

        @AssertTrue(message = "app.rules.slot-minutes must divide 60")
        public boolean isSlotValid() {
            return slotMinutes >= 1 && 60 % slotMinutes == 0;
        }

        @AssertTrue(message = "app.rules.workday-end must be after workday-start")
        public boolean isWorkdayValid() {
            return workdayStart != null && workdayEnd != null && workdayEnd.isAfter(workdayStart);
        }

        @AssertTrue(message = "app.rules.max-series-days must be >= max-advance-days and the daily limit >= the longest booking")
        public boolean isHorizonValid() {
            return maxSeriesDays >= maxAdvanceDays && maxBookedMinutesPerDay >= maxDurationMinutes;
        }

        @AssertTrue(message = "app.rules durations must be multiples of slot-minutes and min <= max")
        public boolean isDurationValid() {
            return slotMinutes >= 1
                    && minDurationMinutes % slotMinutes == 0
                    && maxDurationMinutes % slotMinutes == 0
                    && minDurationMinutes <= maxDurationMinutes;
        }
    }

    public record Bootstrap(String adminEmail, String adminPassword, String adminName) {
        public boolean enabled() {
            return adminEmail != null && !adminEmail.isBlank() && adminPassword != null && !adminPassword.isBlank();
        }
    }

    public record RateLimit(@Min(1) int writesPerMinute) {}

    public record LoginProtection(@Min(1) int maxFailures, @NotNull Duration lockDuration) {}

    public record Cors(@Size(max = 50) List<String> allowedOrigins) {}
}

package com.company.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.booking.common.AppProperties;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Guards the configuration validation (fail fast on nonsense settings). */
class AppPropertiesValidationTest {

    private static final ZoneId Z = ZoneId.of("UTC");

    @Test
    void slotMustDivideAnHour() {
        assertThat(rules(7, 15, 240, "08:00", "20:00").isSlotValid()).isFalse();
        assertThat(rules(15, 15, 240, "08:00", "20:00").isSlotValid()).isTrue();
    }

    @Test
    void workdayMustBeOrdered() {
        assertThat(rules(15, 15, 240, "20:00", "08:00").isWorkdayValid()).isFalse();
    }

    @Test
    void durationsMustAlignWithSlotAndBeOrdered() {
        assertThat(rules(15, 20, 240, "08:00", "20:00").isDurationValid()).isFalse();
        assertThat(rules(15, 60, 30, "08:00", "20:00").isDurationValid()).isFalse();
        assertThat(rules(15, 15, 240, "08:00", "20:00").isDurationValid()).isTrue();
    }

    @Test
    void shortJwtSecretIsRejected() {
        assertThat(new AppProperties.Jwt("short", Duration.ofMinutes(1), "i").isSecretLongEnough()).isFalse();
        assertThat(new AppProperties.Jwt("x".repeat(32), Duration.ofMinutes(1), "i").isSecretLongEnough()).isTrue();
    }

    private static AppProperties.Rules rules(int slot, int min, int max, String s, String e) {
        return new AppProperties.Rules(Z, LocalTime.parse(s), LocalTime.parse(e), slot, min, max, 30, 90, 20, 480, 15);
    }

    @Test
    void horizonsAndDailyLimitMustBeConsistent() {
        assertThat(new AppProperties.Rules(Z, LocalTime.of(8, 0), LocalTime.of(20, 0), 15, 15, 240, 30, 90, 20, 480, 15).isHorizonValid()).isTrue();
        // series horizon shorter than the single-booking horizon
        assertThat(new AppProperties.Rules(Z, LocalTime.of(8, 0), LocalTime.of(20, 0), 15, 15, 240, 60, 30, 20, 480, 15).isHorizonValid()).isFalse();
        // a daily limit smaller than one maximal booking would make that booking impossible
        assertThat(new AppProperties.Rules(Z, LocalTime.of(8, 0), LocalTime.of(20, 0), 15, 15, 240, 30, 90, 20, 120, 15).isHorizonValid()).isFalse();
    }
}

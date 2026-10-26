package com.company.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.booking.booking.BookingRules;
import com.company.booking.common.ApiException;
import com.company.booking.common.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BookingRulesTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    // "now" = 2030-01-10 09:00 +07:00 (a fixed clock keeps the tests deterministic)
    private static final Instant NOW = OffsetDateTime.parse("2030-01-10T09:00:00+07:00").toInstant();

    private final BookingRules rules = new BookingRules(props(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static AppProperties props() {
        return new AppProperties(
                new AppProperties.Jwt("x".repeat(40), Duration.ofMinutes(60), "t"),
                new AppProperties.Rules(ZONE, LocalTime.of(8, 0), LocalTime.of(20, 0), 15, 15, 240, 30, 90, 20, 480, 15),
                new AppProperties.Bootstrap(null, null, null),
                new AppProperties.LoginProtection(5, Duration.ofMinutes(15)),
                new AppProperties.RateLimit(30), null);
    }

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    private void assertRejected(String start, String end, String code) {
        assertThatThrownBy(() -> rules.validate(t(start), t(end)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void acceptsValidBooking() {
        var w = rules.validate(t("2030-01-11T10:00:00+07:00"), t("2030-01-11T11:00:00+07:00"));
        assertThat(Duration.between(w.start(), w.end())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void acceptsTimesGivenInAnotherOffset() {
        // 03:00Z == 10:00 +07:00
        assertThatCode(() -> rules.validate(t("2030-01-11T03:00:00Z"), t("2030-01-11T04:00:00Z"))).doesNotThrowAnyException();
    }

    @Test
    void acceptsBoundariesOfWorkingDay() {
        assertThatCode(() -> rules.validate(t("2030-01-11T08:00:00+07:00"), t("2030-01-11T12:00:00+07:00"))).doesNotThrowAnyException();
        assertThatCode(() -> rules.validate(t("2030-01-11T16:00:00+07:00"), t("2030-01-11T20:00:00+07:00"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsEndBeforeOrEqualStart() {
        assertRejected("2030-01-11T11:00:00+07:00", "2030-01-11T10:00:00+07:00", "INVALID_TIME_RANGE");
        assertRejected("2030-01-11T10:00:00+07:00", "2030-01-11T10:00:00+07:00", "INVALID_TIME_RANGE");
    }

    @Test
    void rejectsMisalignedTimes() {
        assertRejected("2030-01-11T10:05:00+07:00", "2030-01-11T11:00:00+07:00", "TIME_NOT_ALIGNED");
        assertRejected("2030-01-11T10:00:30+07:00", "2030-01-11T11:00:00+07:00", "TIME_NOT_ALIGNED");
    }

    @Test
    void rejectsOutsideWorkingHours() {
        assertRejected("2030-01-11T07:45:00+07:00", "2030-01-11T09:00:00+07:00", "OUTSIDE_WORKING_HOURS");
        assertRejected("2030-01-11T19:00:00+07:00", "2030-01-11T20:15:00+07:00", "OUTSIDE_WORKING_HOURS");
    }

    @Test
    void rejectsCrossDayBooking() {
        assertRejected("2030-01-11T19:00:00+07:00", "2030-01-12T09:00:00+07:00", "CROSS_DAY_BOOKING");
    }

    @Test
    void timezoneIsCompanyZoneNotUtc() {
        // 01:00Z is 08:00 local (ok) but 20:00Z is 03:00 local the next day
        assertThatCode(() -> rules.validate(t("2030-01-11T01:00:00Z"), t("2030-01-11T02:00:00Z"))).doesNotThrowAnyException();
        assertRejected("2030-01-11T20:00:00Z", "2030-01-11T21:00:00Z", "OUTSIDE_WORKING_HOURS");
    }

    @Test
    void enforcesDurationLimits() {
        assertRejected("2030-01-11T10:00:00+07:00", "2030-01-11T15:15:00+07:00", "DURATION_TOO_LONG");
        assertThatCode(() -> rules.validate(t("2030-01-11T10:00:00+07:00"), t("2030-01-11T14:00:00+07:00"))).doesNotThrowAnyException();
        assertThatCode(() -> rules.validate(t("2030-01-11T10:00:00+07:00"), t("2030-01-11T10:15:00+07:00"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsPast() {
        assertRejected("2030-01-09T10:00:00+07:00", "2030-01-09T11:00:00+07:00", "IN_THE_PAST");
        assertRejected("2030-01-10T08:30:00+07:00", "2030-01-10T10:00:00+07:00", "IN_THE_PAST");
    }

    @Test
    void currentSlotStaysBookableForTheGracePeriod() {
        // pressing "book 08:45" at 09:00 is fine (15 minutes of grace) ...
        assertThatCode(() -> rules.validate(t("2030-01-10T08:45:00+07:00"), t("2030-01-10T10:00:00+07:00"))).doesNotThrowAnyException();
        // ... but 08:30 is too old
        assertRejected("2030-01-10T08:30:00+07:00", "2030-01-10T09:30:00+07:00", "IN_THE_PAST");
    }

    @Test
    void singleBookingsAreLimitedToTheAdvanceWindow_butSeriesOccurrencesMayReachFurther() {
        assertRejected("2030-02-12T10:00:00+07:00", "2030-02-12T11:00:00+07:00", "TOO_FAR_IN_ADVANCE"); // 33 days
        assertRejected("2030-02-10T08:00:00+07:00", "2030-02-10T09:00:00+07:00", "TOO_FAR_IN_ADVANCE"); // the day after the last one
        // the last bookable day is open for its whole working day, even for times later than "now" (09:00)
        assertThatCode(() -> rules.validate(t("2030-02-09T18:00:00+07:00"), t("2030-02-09T19:00:00+07:00"))).doesNotThrowAnyException();
        assertThatCode(() -> rules.validateOccurrence(t("2030-03-20T10:00:00+07:00"), t("2030-03-20T11:00:00+07:00"))).doesNotThrowAnyException(); // 69 days
        assertThatThrownBy(() -> rules.validateOccurrence(t("2030-05-20T10:00:00+07:00"), t("2030-05-20T11:00:00+07:00")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("TOO_FAR_IN_ADVANCE"));
    }

    @Test
    void ongoingMeetingKeepsItsStartButTheNewEndMustBeInTheFuture() {
        // running since 08:30 (history, not re-validated), extended to 10:30
        assertThatCode(() -> rules.validateOngoing(t("2030-01-10T08:30:00+07:00"), t("2030-01-10T10:30:00+07:00"))).doesNotThrowAnyException();
        // ending "in the past" is not an edit, it is a cancellation
        assertThatThrownBy(() -> rules.validateOngoing(t("2030-01-10T08:00:00+07:00"), t("2030-01-10T08:45:00+07:00")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("IN_THE_PAST"));
        // still bound by working hours and the maximum duration
        assertThatThrownBy(() -> rules.validateOngoing(t("2030-01-10T08:30:00+07:00"), t("2030-01-10T20:30:00+07:00")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("OUTSIDE_WORKING_HOURS"));
        assertThatThrownBy(() -> rules.validateOngoing(t("2030-01-10T08:30:00+07:00"), t("2030-01-10T14:00:00+07:00")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("DURATION_TOO_LONG"));
    }

    @Test
    void allowsStartingExactlyNow() {
        assertThatCode(() -> rules.validate(t("2030-01-10T09:00:00+07:00"), t("2030-01-10T10:00:00+07:00"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsTooFarInAdvance() {
        assertRejected("2030-06-10T10:00:00+07:00", "2030-06-10T11:00:00+07:00", "TOO_FAR_IN_ADVANCE");
    }
}

package com.company.booking.booking;

import com.company.booking.common.ApiException;
import com.company.booking.common.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

/** Pure business rules about *when* a room may be booked. Everything is configurable via app.rules.*. */
@Component
public class BookingRules {

    private final AppProperties.Rules rules;
    private final Clock clock;

    public BookingRules(AppProperties props, Clock clock) {
        this.rules = props.rules();
        this.clock = clock;
    }

    public ZoneId zone() {
        return rules.timezone();
    }

    public AppProperties.Rules config() {
        return rules;
    }

    public TimeWindow validate(OffsetDateTime startIn, OffsetDateTime endIn) {
        ZoneId zone = rules.timezone();
        ZonedDateTime s = startIn.atZoneSameInstant(zone);
        ZonedDateTime e = endIn.atZoneSameInstant(zone);

        if (!e.isAfter(s)) {
            throw ApiException.unprocessable("INVALID_TIME_RANGE", "endTime must be after startTime");
        }
        if (!aligned(s) || !aligned(e)) {
            throw ApiException.unprocessable("TIME_NOT_ALIGNED",
                    "Times must fall on " + rules.slotMinutes() + "-minute boundaries with zero seconds");
        }
        if (!s.toLocalDate().equals(e.toLocalDate())) {
            throw ApiException.unprocessable("CROSS_DAY_BOOKING", "A booking must start and end on the same day");
        }
        if (s.toLocalTime().isBefore(rules.workdayStart()) || e.toLocalTime().isAfter(rules.workdayEnd())) {
            throw ApiException.unprocessable("OUTSIDE_WORKING_HOURS",
                    "Bookings must be within " + rules.workdayStart() + "-" + rules.workdayEnd() + " (" + zone + ")");
        }
        long minutes = Duration.between(s, e).toMinutes();
        if (minutes < rules.minDurationMinutes()) {
            throw ApiException.unprocessable("DURATION_TOO_SHORT",
                    "Minimum duration is " + rules.minDurationMinutes() + " minutes");
        }
        if (minutes > rules.maxDurationMinutes()) {
            throw ApiException.unprocessable("DURATION_TOO_LONG",
                    "Maximum duration is " + rules.maxDurationMinutes() + " minutes");
        }
        Instant now = clock.instant();
        if (s.toInstant().isBefore(now)) {
            throw ApiException.unprocessable("IN_THE_PAST", "Cannot book a time that has already started");
        }
        if (s.toInstant().isAfter(now.plus(Duration.ofDays(rules.maxAdvanceDays())))) {
            throw ApiException.unprocessable("TOO_FAR_IN_ADVANCE",
                    "Bookings can be made at most " + rules.maxAdvanceDays() + " days ahead");
        }
        return new TimeWindow(s.toInstant(), e.toInstant());
    }

    private boolean aligned(ZonedDateTime t) {
        return t.getSecond() == 0 && t.getNano() == 0 && t.getMinute() % rules.slotMinutes() == 0;
    }

    /**
     * Admin holds are looser than bookings: they may span days and hours outside the working day
     * (e.g. "closed all of next week"), but must still be aligned, ordered, not already over, and bounded.
     */
    public TimeWindow validateBlock(OffsetDateTime startIn, OffsetDateTime endIn) {
        ZoneId zone = rules.timezone();
        ZonedDateTime s = startIn.atZoneSameInstant(zone);
        ZonedDateTime e = endIn.atZoneSameInstant(zone);
        if (!e.isAfter(s)) {
            throw ApiException.unprocessable("INVALID_TIME_RANGE", "endTime must be after startTime");
        }
        if (!aligned(s) || !aligned(e)) {
            throw ApiException.unprocessable("TIME_NOT_ALIGNED",
                    "Times must fall on " + rules.slotMinutes() + "-minute boundaries with zero seconds");
        }
        Instant now = clock.instant();
        if (!e.toInstant().isAfter(now)) {
            throw ApiException.unprocessable("IN_THE_PAST", "The block has already ended");
        }
        Instant horizon = now.plus(Duration.ofDays(rules.maxAdvanceDays()));
        if (s.toInstant().isAfter(horizon)) {
            throw ApiException.unprocessable("TOO_FAR_IN_ADVANCE",
                    "Blocks can start at most " + rules.maxAdvanceDays() + " days ahead");
        }
        if (Duration.between(s, e).toDays() > rules.maxAdvanceDays()) {
            throw ApiException.unprocessable("DURATION_TOO_LONG",
                    "A block can last at most " + rules.maxAdvanceDays() + " days");
        }
        return new TimeWindow(s.toInstant(), e.toInstant());
    }
}

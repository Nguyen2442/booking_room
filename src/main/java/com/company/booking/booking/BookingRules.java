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

    /** A new single booking (or an edit of one that has not started). */
    public TimeWindow validate(OffsetDateTime startIn, OffsetDateTime endIn) {
        return validate(startIn, endIn, rules.maxAdvanceDays(), true);
    }

    /** One occurrence of a recurring booking: may reach further ahead than a single booking. */
    public TimeWindow validateOccurrence(OffsetDateTime startIn, OffsetDateTime endIn) {
        return validate(startIn, endIn, rules.maxSeriesDays(), true);
    }

    /**
     * Editing a meeting that is already running (extend it, or end it early): the start is history, so only
     * the shape of the booking and its new end are checked.
     */
    public TimeWindow validateOngoing(OffsetDateTime startIn, OffsetDateTime endIn) {
        TimeWindow w = validate(startIn, endIn, null, false);
        if (!w.end().isAfter(clock.instant())) {
            throw ApiException.unprocessable("IN_THE_PAST", "The new end time has already passed");
        }
        return w;
    }

    private TimeWindow validate(OffsetDateTime startIn, OffsetDateTime endIn, Integer horizonDays, boolean checkStartInPast) {
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
        // The current slot stays bookable for a few minutes: picking 14:00 and pressing the button at 14:01 is normal.
        if (checkStartInPast && s.toInstant().isBefore(now.minus(Duration.ofMinutes(rules.startGraceMinutes())))) {
            throw ApiException.unprocessable("IN_THE_PAST", "Cannot book a time that has already started");
        }
        // Counted in calendar days (what the date picker shows), not in hours from this very moment.
        if (horizonDays != null && s.toLocalDate().isAfter(lastBookableDay(horizonDays))) {
            throw ApiException.unprocessable("TOO_FAR_IN_ADVANCE", "Bookings can be made at most "
                    + horizonDays + " days ahead");
        }
        return new TimeWindow(s.toInstant(), e.toInstant());
    }

    /** The last calendar day (company time zone) on which something may start, {@code days} after today. */
    public java.time.LocalDate lastBookableDay(int days) {
        return clock.instant().atZone(rules.timezone()).toLocalDate().plusDays(days);
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
        if (s.toLocalDate().isAfter(lastBookableDay(rules.maxSeriesDays()))) {
            throw ApiException.unprocessable("TOO_FAR_IN_ADVANCE",
                    "Blocks can start at most " + rules.maxSeriesDays() + " days ahead");
        }
        if (Duration.between(s, e).toDays() > rules.maxSeriesDays()) {
            throw ApiException.unprocessable("DURATION_TOO_LONG",
                    "A block can last at most " + rules.maxSeriesDays() + " days");
        }
        return new TimeWindow(s.toInstant(), e.toInstant());
    }
}

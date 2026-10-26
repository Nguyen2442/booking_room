package com.company.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.booking.booking.Booking;
import com.company.booking.booking.BookingDtos.CreateBookingRequest;
import com.company.booking.booking.BookingDtos.CreateSeriesRequest;
import com.company.booking.booking.BookingDtos.Frequency;
import com.company.booking.booking.BookingRepository;
import com.company.booking.booking.BookingService;
import com.company.booking.common.ApiException;
import com.company.booking.room.Room;
import com.company.booking.user.Role;
import com.company.booking.user.User;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Abuse limits, overrun handling and the other "what if things go wrong" cases. */
class HardeningIntegrationTest extends IntegrationTestBase {

    @Autowired BookingService service;
    @Autowired BookingRepository bookingRepo;
    @Autowired PlatformTransactionManager tm;

    private User admin;
    private User alice;
    private User bob;
    private Room big;
    private Room small;

    @BeforeEach
    void data() {
        admin = user("admin@company.local", Role.ADMIN);
        alice = user("alice@company.local", Role.USER);
        bob = user("bob@company.local", Role.USER);
        big = room("Big", 20);
        small = room("Small", 4);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> body(Room r, String start, String end, int attendees) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomId", r.getId());
        m.put("title", "Quota test");
        m.put("startTime", start);
        m.put("endTime", end);
        m.put("attendees", attendees);
        return m;
    }

    private ResultActions send(User who, String method, String url, Map<String, Object> b) throws Exception {
        var builder = method.equals("PUT") ? put(url) : post(url);
        return mvc.perform(withJson(builder, who, json.writeValueAsString(b)));
    }

    private ResultActions book(User who, Room r, int day, int h1, int h2) throws Exception {
        return send(who, "POST", "/api/v1/bookings", body(r, at(day, h1), at(day, h2), 2));
    }

    private String bookOk(User who, Room r, int day, int h1, int h2) throws Exception {
        String res = book(who, r, day, h1, h2).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    private void direct(User who, Room r, int day, int hour) {
        service.create(who.getId(), new CreateBookingRequest(r.getId(), "Quota", null,
                OffsetDateTime.parse(at(day, hour)), OffsetDateTime.parse(at(day, hour, 15)), 2, null));
    }

    private Booking seed(User who, Room r, int h1, int m1, int h2, int m2) {
        Booking b = new Booking();
        b.setRoom(r);
        b.setUser(who);
        b.setTitle("seeded");
        b.setAttendees(2);
        b.setStartTime(instant(at(0, h1, m1)));
        b.setEndTime(instant(at(0, h2, m2)));
        return bookingRepo.save(b);
    }

    // ---------------------------------------------------------------- per-day hours limit

    @Test
    void aPersonCannotBookMoreThanTheDailyLimit_acrossRooms() throws Exception {
        bookOk(alice, big, 2, 9, 12);   // 3h
        bookOk(alice, big, 2, 12, 15);  // 6h
        bookOk(alice, big, 2, 15, 17);  // 8h: exactly the limit
        send(alice, "POST", "/api/v1/bookings", body(small, at(2, 17), at(2, 17, 15), 2))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT_REACHED"))
                .andExpect(jsonPath("$.limitHours").value("8"))
                .andExpect(jsonPath("$.date").value(LocalDate.now(zone).plusDays(2).toString()));
        // other days and other people are unaffected
        bookOk(alice, big, 3, 9, 12);
        bookOk(bob, big, 2, 17, 19);
    }

    @Test
    void cancellingFreesTheDailyQuota_andEditingYourOwnBookingIsNotCountedTwice() throws Exception {
        String first = bookOk(alice, big, 2, 9, 13);   // 4h
        String second = bookOk(alice, big, 2, 13, 17); // 8h
        book(alice, small, 2, 17, 18).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DAILY_LIMIT_REACHED"));
        // shrinking an existing booking is fine even though the day is "full" (it is excluded from its own total)
        send(alice, "PUT", "/api/v1/bookings/" + first, body(big, at(2, 9), at(2, 12), 2)).andExpect(status().isOk());
        // growing it past the limit is not
        send(alice, "PUT", "/api/v1/bookings/" + second, body(big, at(2, 13), at(2, 18), 2)).andExpect(status().isUnprocessableEntity());
        send(alice, "PUT", "/api/v1/bookings/" + second, body(big, at(2, 13), at(2, 17), 2)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/bookings/" + second + "/cancel").header("Authorization", bearer(alice))).andExpect(status().isOk());
        book(alice, small, 2, 17, 18).andExpect(status().isCreated());
    }

    @Test
    void movingABookingOntoADayThatIsFullIsRefused() throws Exception {
        bookOk(alice, big, 2, 9, 13);
        bookOk(alice, big, 2, 13, 17);
        String other = bookOk(alice, big, 3, 9, 10);
        send(alice, "PUT", "/api/v1/bookings/" + other, body(small, at(2, 17), at(2, 18), 2))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DAILY_LIMIT_REACHED"));
    }

    @Test
    void aSeriesIsCheckedAgainstTheDailyLimitOfEveryOccurrence() throws Exception {
        bookOk(alice, big, 2, 8, 12);
        bookOk(alice, big, 2, 12, 15); // 7h on that day
        Map<String, Object> s = body(big, at(2, 15), at(2, 17), 2);
        s.put("frequency", "WEEKLY");
        s.put("occurrences", 3);
        send(alice, "POST", "/api/v1/bookings/series", s)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DAILY_LIMIT_REACHED"));
        mvc.perform(get("/api/v1/bookings/my?status=CONFIRMED").header("Authorization", bearer(alice))).andExpect(jsonPath("$.totalElements").value(2));
    }

    // ---------------------------------------------------------------- upcoming-bookings cap

    @Test
    void aPersonCanHoldAtMost20UpcomingBookingsInTheBookingWindow() {
        for (int day = 1; day <= 4; day++) {
            for (int hour = 8; hour < 13; hour++) {
                direct(alice, big, day, hour);
            }
        }
        assertThatThrownBy(() -> direct(alice, big, 5, 8)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("BOOKING_LIMIT_REACHED"));
        direct(bob, big, 5, 8); // somebody else is not affected
    }

    @Test
    void farFutureOccurrencesOfASeriesDoNotUseUpTheNearTermCap() {
        // 13 weekly meetings reach ~85 days ahead, but only those inside the 30-day window count (5 of them)
        service.createSeries(alice.getId(), new CreateSeriesRequest(big.getId(), "Weekly", null, OffsetDateTime.parse(at(1, 10)),
                OffsetDateTime.parse(at(1, 11)), 2, Frequency.WEEKLY, 13, null, null));
        for (int i = 0; i < 15; i++) {
            direct(alice, small, 2 + i / 5, 8 + i % 5);
        }
        assertThatThrownBy(() -> direct(alice, small, 5, 8)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("BOOKING_LIMIT_REACHED"));
    }

    @Test
    void theCapIsAHardGuarantee_evenWhenOnePersonFiresParallelRequests() throws Exception {
        for (int i = 0; i < 15; i++) {
            direct(alice, small, 1 + i / 5, 8 + i % 5);
        }
        int attempts = 10;
        var pool = Executors.newFixedThreadPool(attempts);
        var go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            final int hour = 8 + i;
            fs.add(pool.submit(() -> {
                go.await();
                try {
                    direct(alice, big, 5, hour);
                    ok.incrementAndGet();
                } catch (ApiException e) {
                    if ("BOOKING_LIMIT_REACHED".equals(e.getCode())) refused.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdownNow();
        assertThat(ok.get()).isEqualTo(5);       // 15 + 5 = 20, never 21+
        assertThat(refused.get()).isEqualTo(5);
    }

    // ---------------------------------------------------------------- request-rate limit

    @Test
    void aScriptCannotFloodTheBookingEndpoints_butReadsAndOtherPeopleAreUnaffected() throws Exception {
        User spammer = user("spam@company.local", Role.USER);
        for (int i = 0; i < 30; i++) {
            mvc.perform(withJson(post("/api/v1/bookings"), spammer, "{}")).andExpect(status().isBadRequest());
        }
        mvc.perform(withJson(post("/api/v1/bookings"), spammer, "{}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().string("Retry-After", "60"));
        mvc.perform(get("/api/v1/rooms").header("Authorization", bearer(spammer))).andExpect(status().isOk());
        mvc.perform(withJson(post("/api/v1/bookings"), bob, "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void oversizedBodiesAreRejectedBeforeParsing() throws Exception {
        mvc.perform(post("/api/v1/bookings").header("Authorization", bearer(alice)).contentType("application/json").content("x".repeat(70_000)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    // ---------------------------------------------------------------- real-life timing

    @Test
    void theCurrentSlotCanStillBeBookedForAFewMinutes() throws Exception {
        pinNow(10, 7);
        send(alice, "POST", "/api/v1/bookings", body(big, at(0, 10), at(0, 11), 2)).andExpect(status().isCreated());
        send(alice, "POST", "/api/v1/bookings", body(small, at(0, 9, 45), at(0, 10, 45), 2))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IN_THE_PAST"));
    }

    @Test
    void aMeetingThatOverrunsCanBeExtended_orEndedEarlyToFreeTheRoom() throws Exception {
        pinNow(10, 20);
        Booking running = seed(alice, big, 10, 0, 11, 0);
        String url = "/api/v1/bookings/" + running.getId();

        send(alice, "PUT", url, body(big, at(0, 10), at(0, 11, 30), 2).entrySet().stream().collect(
                LinkedHashMap::new, (m, e) -> m.put(e.getKey().equals("roomId") ? "roomId" : e.getKey(), e.getValue()), Map::putAll))
                .andExpect(status().isOk()).andExpect(jsonPath("$.endTime").value(org.hamcrest.Matchers.containsString("T11:30")));
        send(alice, "PUT", url, body(big, at(0, 10), at(0, 10, 30), 2)).andExpect(status().isOk()); // end early
        // the room is genuinely free again after 10:30
        send(bob, "POST", "/api/v1/bookings", body(big, at(0, 10, 30), at(0, 11, 30), 2)).andExpect(status().isCreated());
    }

    @Test
    void aRunningMeetingCannotMoveItsStartOrRoom_orEndInThePast_orRunIntoTheNextBooking() throws Exception {
        pinNow(10, 20);
        Booking running = seed(alice, big, 10, 0, 11, 0);
        seed(bob, big, 11, 0, 12, 0);
        String url = "/api/v1/bookings/" + running.getId();

        send(alice, "PUT", url, body(big, at(0, 10, 15), at(0, 11), 2))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BOOKING_ALREADY_STARTED"));
        send(alice, "PUT", url, body(small, at(0, 10), at(0, 11), 2))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BOOKING_ALREADY_STARTED"));
        send(alice, "PUT", url, body(big, at(0, 10), at(0, 10, 15), 2))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IN_THE_PAST"));
        send(alice, "PUT", url, body(big, at(0, 10), at(0, 11, 30), 2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_CONFLICT"));
    }

    @Test
    void aMeetingThatHasEndedCannotBeEditedAnymore() throws Exception {
        pinNow(10, 20);
        Booking ended = seed(alice, big, 8, 0, 9, 0);
        send(alice, "PUT", "/api/v1/bookings/" + ended.getId(), body(big, at(0, 8), at(0, 9, 30), 2))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BOOKING_ALREADY_ENDED"));
    }

    // ---------------------------------------------------------------- consistency between rooms and bookings

    @Test
    void shrinkingARoomBelowAnUpcomingBookingsHeadCountIsRefused() throws Exception {
        Map<String, Object> b = body(big, at(2, 10), at(2, 11), 15);
        send(alice, "POST", "/api/v1/bookings", b).andExpect(status().isCreated());
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("name", "Big");
        edit.put("capacity", 10);
        send(admin, "PUT", "/api/v1/rooms/" + big.getId(), edit)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAPACITY_CONFLICTS_BOOKINGS"));
        edit.put("capacity", 15);
        send(admin, "PUT", "/api/v1/rooms/" + big.getId(), edit).andExpect(status().isOk());
    }

    @Test
    void theOwnerIsToldWhenSomebodyElseCancelledTheirBooking() throws Exception {
        String mine = bookOk(alice, big, 2, 9, 10);
        String byAdmin = bookOk(alice, big, 2, 11, 12);
        mvc.perform(post("/api/v1/bookings/" + byAdmin + "/cancel").header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"reason\":\"fire drill\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/bookings/" + mine + "/cancel").header("Authorization", bearer(alice))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings/" + byAdmin).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.cancelledByAnother").value(true)).andExpect(jsonPath("$.cancelReason").value("fire drill"));
        mvc.perform(get("/api/v1/bookings/" + mine).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.cancelledByAnother").value(false));
    }

    // ---------------------------------------------------------------- a busy database must not hang the API

    @Test
    void whenTheDatabaseKeepsARoomLockedTheApiAnswers503InsteadOfHanging() throws Exception {
        var tt = new TransactionTemplate(tm);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = new Thread(() -> tt.executeWithoutResult(st -> {
            jdbc.queryForList("select id from rooms where id = ? for update", big.getId());
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        locked.await();
        try {
            long t0 = System.currentTimeMillis();
            book(alice, big, 2, 10, 11)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
                    .andExpect(header().string("Retry-After", "2"));
            // lock_timeout is 5 s: it fails fast rather than waiting for ever
            assertThat(System.currentTimeMillis() - t0).isBetween(4000L, 12_000L);
        } finally {
            release.countDown();
            holder.join();
        }
        // and the system recovers as soon as the lock is gone
        book(alice, big, 2, 10, 11).andExpect(status().isCreated());
    }
}

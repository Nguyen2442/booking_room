package com.company.booking;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.booking.room.Room;
import com.company.booking.user.Role;
import com.company.booking.user.User;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Attendees, moving a booking, recurring series and admin room blocks. */
class FeaturesIntegrationTest extends IntegrationTestBase {

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

    private ResultActions send(User who, String url, Map<String, Object> body) throws Exception {
        return mvc.perform(withJson(post(url), who, json.writeValueAsString(body)));
    }

    private Map<String, Object> booking(Room r, String start, String end, Integer attendees) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomId", r.getId());
        m.put("title", "Meeting");
        m.put("startTime", start);
        m.put("endTime", end);
        if (attendees != null) m.put("attendees", attendees);
        return m;
    }

    private String createBooking(User who, Room r, String start, String end) throws Exception {
        String body = send(who, "/api/v1/bookings", booking(r, start, end, 2)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private Map<String, Object> series(Room r, int firstDay, int hour, String frequency, int occurrences, String onConflict) {
        Map<String, Object> m = booking(r, at(firstDay, hour), at(firstDay, hour + 1), 2);
        m.put("frequency", frequency);
        m.put("occurrences", occurrences);
        if (onConflict != null) m.put("onConflict", onConflict);
        return m;
    }

    private Map<String, Object> block(Room r, String start, String end, String reason, boolean cancelConflicting) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomId", r.getId());
        m.put("startTime", start);
        m.put("endTime", end);
        m.put("reason", reason);
        m.put("cancelConflicting", cancelConflicting);
        return m;
    }

    private int daysUntil(DayOfWeek dow) {
        for (int d = 1; d <= 7; d++) {
            if (LocalDate.now(zone).plusDays(d).getDayOfWeek() == dow) return d;
        }
        throw new IllegalStateException();
    }

    // ---------------------------------------------------------------- attendees

    @Test
    void attendeesAreRequiredAndCannotExceedCapacity() throws Exception {
        send(alice, "/api/v1/bookings", booking(small, at(1, 10), at(1, 11), null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("attendees"));
        send(alice, "/api/v1/bookings", booking(small, at(1, 10), at(1, 11), 0)).andExpect(status().isBadRequest());
        send(alice, "/api/v1/bookings", booking(small, at(1, 10), at(1, 11), 5))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OVER_CAPACITY"));
        send(alice, "/api/v1/bookings", booking(small, at(1, 10), at(1, 11), 4))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.attendees").value(4));
    }

    // ---------------------------------------------------------------- moving a booking

    @Test
    void bookingCanBeMovedToAnotherRoom() throws Exception {
        String id = createBooking(alice, small, at(1, 10), at(1, 11));
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("roomId", big.getId());
        move.put("title", "Moved");
        move.put("startTime", at(1, 10));
        move.put("endTime", at(1, 11));
        move.put("attendees", 12);
        mvc.perform(withJson(put("/api/v1/bookings/" + id), alice, json.writeValueAsString(move)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.room.name").value("Big")).andExpect(jsonPath("$.attendees").value(12));
        // the old room is free again
        send(bob, "/api/v1/bookings", booking(small, at(1, 10), at(1, 11), 2)).andExpect(status().isCreated());
    }

    @Test
    void movingIsRefusedWhenTheTargetRoomIsBusyOrTooSmall() throws Exception {
        String id = createBooking(alice, big, at(1, 10), at(1, 11));
        createBooking(bob, small, at(1, 10), at(1, 11));
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("roomId", small.getId());
        move.put("title", "x");
        move.put("startTime", at(1, 10));
        move.put("endTime", at(1, 11));
        move.put("attendees", 2);
        mvc.perform(withJson(put("/api/v1/bookings/" + id), alice, json.writeValueAsString(move)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_CONFLICT"));
        move.put("startTime", at(1, 14));
        move.put("endTime", at(1, 15));
        move.put("attendees", 10);
        mvc.perform(withJson(put("/api/v1/bookings/" + id), alice, json.writeValueAsString(move)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OVER_CAPACITY"));
    }

    // ---------------------------------------------------------------- recurring series

    @Test
    void weeklySeriesCreatesEveryOccurrenceOnTheSameWeekday() throws Exception {
        send(alice, "/api/v1/bookings/series", series(big, 1, 10, "WEEKLY", 3, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seriesId").exists())
                .andExpect(jsonPath("$.created", hasSize(3)))
                .andExpect(jsonPath("$.skipped", hasSize(0)))
                .andExpect(jsonPath("$.created[1].startTime").value(org.hamcrest.Matchers.startsWith(at(8, 10).substring(0, 16))))
                .andExpect(jsonPath("$.created[2].startTime").value(org.hamcrest.Matchers.startsWith(at(15, 10).substring(0, 16))));
        mvc.perform(get("/api/v1/bookings/my?status=CONFIRMED").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void biweeklyAndWeekdaysFrequencies() throws Exception {
        send(alice, "/api/v1/bookings/series", series(big, 1, 10, "BIWEEKLY", 3, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created[2].startTime").value(org.hamcrest.Matchers.startsWith(at(29, 10).substring(0, 16))));
        int monday = daysUntil(DayOfWeek.MONDAY);
        send(bob, "/api/v1/bookings/series", series(small, monday, 9, "WEEKDAYS", 6, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.created", hasSize(6)))
                // Mon..Fri then the next Monday: the weekend is skipped
                .andExpect(jsonPath("$.created[5].startTime").value(org.hamcrest.Matchers.startsWith(at(monday + 7, 9).substring(0, 16))));
    }

    @Test
    void weekdaySeriesCannotStartOnAWeekend() throws Exception {
        send(alice, "/api/v1/bookings/series", series(big, daysUntil(DayOfWeek.SATURDAY), 10, "WEEKDAYS", 3, null))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("START_ON_WEEKEND"));
    }

    @Test
    void seriesIsRejectedAsAWholeWhenAnOccurrenceClashes_andReportsWhich() throws Exception {
        createBooking(bob, big, at(8, 10), at(8, 11)); // clashes with the 2nd weekly occurrence
        send(alice, "/api/v1/bookings/series", series(big, 1, 10, "WEEKLY", 3, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERIES_CONFLICT"))
                .andExpect(jsonPath("$.conflicts", hasSize(1)))
                .andExpect(jsonPath("$.conflicts[0]").value(LocalDate.now(zone).plusDays(8).toString()));
        // atomic: nothing was created for alice
        mvc.perform(get("/api/v1/bookings/my").header("Authorization", bearer(alice))).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void seriesCanSkipClashingOccurrences() throws Exception {
        createBooking(bob, big, at(8, 10), at(8, 11));
        send(alice, "/api/v1/bookings/series", series(big, 1, 10, "WEEKLY", 3, "SKIP"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.created", hasSize(2)))
                .andExpect(jsonPath("$.skipped", hasSize(1)))
                .andExpect(jsonPath("$.skipped[0].date").value(LocalDate.now(zone).plusDays(8).toString()));
    }

    @Test
    void skippingEverythingIsStillAnError() throws Exception {
        createBooking(bob, big, at(1, 10), at(1, 11));
        createBooking(bob, big, at(8, 10), at(8, 11));
        send(alice, "/api/v1/bookings/series", series(big, 1, 10, "WEEKLY", 2, "SKIP"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERIES_CONFLICT"));
    }

    @Test
    void seriesRespectsTheSameTimeRulesAsASingleBooking() throws Exception {
        // 14 weekly occurrences run past the 90-day booking horizon
        send(alice, "/api/v1/bookings/series", series(big, 1, 10, "WEEKLY", 14, null))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TOO_FAR_IN_ADVANCE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Occurrence on ")));
        send(alice, "/api/v1/bookings/series", series(big, 1, 10, "WEEKLY", 1, null)).andExpect(status().isBadRequest());
        send(alice, "/api/v1/bookings/series", series(small, 1, 10, "WEEKLY", 2, null).entrySet().stream()
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getKey().equals("attendees") ? 9 : e.getValue()), Map::putAll))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OVER_CAPACITY"));
    }

    @Test
    void cancelSeriesCancelsThisAndFollowingOccurrencesOnly() throws Exception {
        String body = send(alice, "/api/v1/bookings/series", series(big, 1, 10, "WEEKLY", 4, null)).andReturn().getResponse().getContentAsString();
        var created = json.readTree(body).get("created");
        String second = created.get(1).get("id").asText();
        String first = created.get(0).get("id").asText();

        mvc.perform(withJson(post("/api/v1/bookings/" + second + "/cancel-series"), alice, "{\"reason\":\"project over\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(3));
        mvc.perform(get("/api/v1/bookings/" + first).header("Authorization", bearer(alice))).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(get("/api/v1/bookings/" + second).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancelReason").value("project over"));
        // a second call finds nothing left to cancel
        mvc.perform(post("/api/v1/bookings/" + second + "/cancel-series").header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(0));
    }

    @Test
    void cancelSeriesChecksOwnershipAndSeriesMembership() throws Exception {
        String body = send(alice, "/api/v1/bookings/series", series(big, 1, 10, "WEEKLY", 2, null)).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("created").get(0).get("id").asText();
        mvc.perform(post("/api/v1/bookings/" + id + "/cancel-series").header("Authorization", bearer(bob))).andExpect(status().isForbidden());
        String single = createBooking(alice, small, at(2, 10), at(2, 11));
        mvc.perform(post("/api/v1/bookings/" + single + "/cancel-series").header("Authorization", bearer(alice)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_A_SERIES"));
    }

    // ---------------------------------------------------------------- room blocks

    @Test
    void adminBlocksARoomAndNobodyCanBookIt() throws Exception {
        send(admin, "/api/v1/admin/blocks", block(big, at(1, 9), at(1, 12), "Projector repair", false))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.block.kind").value("BLOCK"))
                .andExpect(jsonPath("$.block.title").value("Projector repair")).andExpect(jsonPath("$.cancelledBookings", hasSize(0)));
        send(alice, "/api/v1/bookings", booking(big, at(1, 10), at(1, 11), 2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_CONFLICT"));
        send(alice, "/api/v1/bookings", booking(big, at(1, 12), at(1, 13), 2)).andExpect(status().isCreated()); // right after the block
        // search/availability treat it like any other occupation
        mvc.perform(get("/api/v1/rooms/available").param("start", at(1, 10)).param("end", at(1, 11)).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.content[?(@.name=='Big')]").isEmpty());
        mvc.perform(get("/api/v1/rooms/" + big.getId() + "/schedule").param("date", LocalDate.now(zone).plusDays(1).toString())
                        .header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.bookings[0].kind").value("BLOCK"));
    }

    @Test
    void onlyAdminsManageBlocks() throws Exception {
        send(alice, "/api/v1/admin/blocks", block(big, at(1, 9), at(1, 12), "x", false)).andExpect(status().isForbidden());
        String body = send(admin, "/api/v1/admin/blocks", block(big, at(1, 9), at(1, 12), "Cleaning", false)).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("block").get("id").asText();
        mvc.perform(post("/api/v1/bookings/" + id + "/cancel").header("Authorization", bearer(alice))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/bookings/" + id).header("Authorization", bearer(alice))).andExpect(status().isForbidden());
        mvc.perform(withJson(put("/api/v1/bookings/" + id), admin, json.writeValueAsString(Map.of("title", "x", "startTime", at(1, 9), "endTime", at(1, 10), "attendees", 1))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BLOCK_NOT_EDITABLE"));
        // lifting the block frees the room
        mvc.perform(post("/api/v1/bookings/" + id + "/cancel").header("Authorization", bearer(admin))).andExpect(status().isOk());
        send(alice, "/api/v1/bookings", booking(big, at(1, 10), at(1, 11), 2)).andExpect(status().isCreated());
    }

    @Test
    void blockIsRefusedWhenBookingsExist_unlessAdminChoosesToCancelThem() throws Exception {
        String id = createBooking(alice, big, at(1, 10), at(1, 11));
        send(admin, "/api/v1/admin/blocks", block(big, at(1, 9), at(1, 12), "Flooring", false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BLOCK_CONFLICTS_WITH_BOOKINGS"))
                .andExpect(jsonPath("$.conflicts", hasSize(1)))
                .andExpect(jsonPath("$.conflicts[0].booker").value("alice"));
        mvc.perform(get("/api/v1/bookings/" + id).header("Authorization", bearer(alice))).andExpect(jsonPath("$.status").value("CONFIRMED"));

        send(admin, "/api/v1/admin/blocks", block(big, at(1, 9), at(1, 12), "Flooring", true))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.cancelledBookings", hasSize(1)));
        mvc.perform(get("/api/v1/bookings/" + id).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancelReason").value("Room unavailable: Flooring"));
    }

    @Test
    void blocksCannotOverlapEachOther() throws Exception {
        send(admin, "/api/v1/admin/blocks", block(big, at(1, 9), at(1, 12), "A", false)).andExpect(status().isCreated());
        send(admin, "/api/v1/admin/blocks", block(big, at(1, 11), at(1, 14), "B", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_BLOCKED"));
    }

    @Test
    void blockMayCoverDaysAndHoursOutsideTheWorkingDay_butNotThePast() throws Exception {
        send(admin, "/api/v1/admin/blocks", block(big, at(1, 6), at(5, 22), "Renovation", false)).andExpect(status().isCreated());
        send(admin, "/api/v1/admin/blocks", block(small, at(-2, 8), at(-1, 8), "Old", false))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IN_THE_PAST"));
        send(admin, "/api/v1/admin/blocks", block(small, at(1, 10, 5), at(1, 11), "x", false))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TIME_NOT_ALIGNED"));
        send(admin, "/api/v1/admin/blocks", block(small, at(2, 10), at(1, 10), "x", false))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        Map<String, Object> noReason = block(small, at(1, 10), at(1, 11), "", false);
        send(admin, "/api/v1/admin/blocks", noReason).andExpect(status().isBadRequest());
    }

    @Test
    void blocksDoNotCountAsEmployeeBookings() throws Exception {
        send(admin, "/api/v1/admin/blocks", block(big, at(1, 9), at(1, 12), "Repair", false)).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/bookings/my").header("Authorization", bearer(admin))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/bookings?kind=BLOCK").header("Authorization", bearer(admin))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/bookings?kind=BOOKING").header("Authorization", bearer(admin))).andExpect(jsonPath("$.totalElements").value(0));
        // a room that only has a block can still be deactivated (no employee is affected)
        mvc.perform(delete("/api/v1/rooms/" + big.getId()).header("Authorization", bearer(admin))).andExpect(status().isNoContent());
    }

    @Test
    void blockingRacingWithBookingsNeverLeavesAnOverlap() throws Exception {
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        UUID roomId = big.getId();
        for (int i = 0; i < 6; i++) {
            final int hour = 9 + i;
            threads.add(new Thread(() -> {
                try {
                    send(alice, "/api/v1/bookings", booking(big, at(1, hour), at(1, hour + 1), 2));
                } catch (Exception ignored) { }
            }));
        }
        threads.add(new Thread(() -> {
            try {
                send(admin, "/api/v1/admin/blocks", block(big, at(1, 8), at(1, 20), "Emergency", true));
            } catch (Exception ignored) { }
        }));
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();
        Integer overlaps = jdbc.queryForObject("""
                select count(*) from bookings a join bookings b
                  on a.room_id = b.room_id and a.id < b.id and a.time_range && b.time_range
                where a.status = 'CONFIRMED' and b.status = 'CONFIRMED' and a.room_id = ?""", Integer.class, roomId);
        org.assertj.core.api.Assertions.assertThat(overlaps).isZero();
    }
}

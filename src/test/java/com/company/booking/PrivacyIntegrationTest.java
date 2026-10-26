package com.company.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.booking.room.Room;
import com.company.booking.user.Role;
import com.company.booking.user.User;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Private bookings: other employees see "busy" only, on every endpoint; the owner and admins see everything. */
class PrivacyIntegrationTest extends IntegrationTestBase {

    private static final String SECRET_TITLE = "Salary negotiation for Minh";
    private static final String SECRET_DESC = "Offer 5000 USD, do not share";

    private User admin;
    private User alice;
    private User bob;
    private Room room;
    private String bookingId;

    @BeforeEach
    void data() throws Exception {
        admin = user("admin@company.local", Role.ADMIN);
        alice = user("alice@company.local", Role.USER);
        bob = user("bob@company.local", Role.USER);
        room = room("Everest", 10);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", room.getId());
        body.put("title", SECRET_TITLE);
        body.put("description", SECRET_DESC);
        body.put("startTime", at(1, 10));
        body.put("endTime", at(1, 11));
        body.put("attendees", 2);
        body.put("private", true);
        String res = mvc.perform(withJson(post("/api/v1/bookings"), alice, json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        bookingId = json.readTree(res).get("id").asText();
    }

    private String day() {
        return LocalDate.now(zone).plusDays(1).toString();
    }

    private String body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private void assertNoLeak(String responseBody) {
        assertThat(responseBody).doesNotContain(SECRET_TITLE).doesNotContain("Salary").doesNotContain(SECRET_DESC)
                .doesNotContain("Offer").doesNotContain("alice").doesNotContain(alice.getId().toString());
    }

    @Test
    void ownerSeesEverything() throws Exception {
        mvc.perform(get("/api/v1/bookings/" + bookingId).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.title").value(SECRET_TITLE)).andExpect(jsonPath("$.description").value(SECRET_DESC))
                .andExpect(jsonPath("$.private").value(true)).andExpect(jsonPath("$.masked").value(false))
                .andExpect(jsonPath("$.booker.fullName").value("alice"));
        mvc.perform(get("/api/v1/bookings/my").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.content[0].title").value(SECRET_TITLE));
        mvc.perform(get("/api/v1/schedule").param("date", day()).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.rooms[0].bookings[0].title").value(SECRET_TITLE));
    }

    @Test
    void adminSeesEverything() throws Exception {
        mvc.perform(get("/api/v1/bookings/" + bookingId).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.title").value(SECRET_TITLE)).andExpect(jsonPath("$.masked").value(false));
        mvc.perform(get("/api/v1/bookings").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.content[0].title").value(SECRET_TITLE));
        mvc.perform(get("/api/v1/rooms/" + room.getId() + "/schedule").param("date", day()).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.bookings[0].title").value(SECRET_TITLE)).andExpect(jsonPath("$.bookings[0].description").value(SECRET_DESC));
    }

    @Test
    void otherEmployeesSeeOnlyThatTheRoomIsBusy_onTheDayScheduleAndTheRoomSchedule() throws Exception {
        String daySchedule = body(mvc.perform(get("/api/v1/schedule").param("date", day()).header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[0].bookings[0].masked").value(true))
                .andExpect(jsonPath("$.rooms[0].bookings[0].title").value("Private"))
                .andExpect(jsonPath("$.rooms[0].bookings[0].startTime").exists())
                .andExpect(jsonPath("$.rooms[0].bookings[0].booker").doesNotExist())
                .andExpect(jsonPath("$.rooms[0].bookings[0].description").doesNotExist())
                .andExpect(jsonPath("$.rooms[0].bookings[0].attendees").doesNotExist()));
        assertNoLeak(daySchedule);

        String roomSchedule = body(mvc.perform(get("/api/v1/rooms/" + room.getId() + "/schedule").param("date", day())
                .header("Authorization", bearer(bob))).andExpect(jsonPath("$.bookings[0].masked").value(true)));
        assertNoLeak(roomSchedule);
        // time ranges are still there: that is what prevents double booking
        assertThat(roomSchedule).contains("startTime").contains("endTime");
    }

    @Test
    void theBusyRoomStillCannotBeBooked_andTheErrorLeaksNothing() throws Exception {
        Map<String, Object> clash = new LinkedHashMap<>();
        clash.put("roomId", room.getId());
        clash.put("title", "Clash");
        clash.put("startTime", at(1, 10));
        clash.put("endTime", at(1, 11));
        clash.put("attendees", 2);
        String res = body(mvc.perform(withJson(post("/api/v1/bookings"), bob, json.writeValueAsString(clash)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_CONFLICT")));
        assertNoLeak(res);
        assertNoLeak(body(mvc.perform(get("/api/v1/rooms/available").param("start", at(1, 10)).param("end", at(1, 11))
                .header("Authorization", bearer(bob)))));
        mvc.perform(get("/api/v1/rooms/" + room.getId() + "/free-slots").param("date", day()).header("Authorization", bearer(bob)))
                .andExpect(status().isOk());
    }

    @Test
    void otherEmployeesCannotFetchItDirectly() throws Exception {
        assertNoLeak(body(mvc.perform(get("/api/v1/bookings/" + bookingId).header("Authorization", bearer(bob)))
                .andExpect(status().isForbidden())));
        assertNoLeak(body(mvc.perform(get("/api/v1/bookings/my").header("Authorization", bearer(bob)))));
    }

    @Test
    void privacyCanBeToggledOffAndOn_byTheOwnerOnly() throws Exception {
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("title", SECRET_TITLE);
        upd.put("description", SECRET_DESC);
        upd.put("startTime", at(1, 10));
        upd.put("endTime", at(1, 11));
        upd.put("attendees", 2);
        upd.put("private", false);
        mvc.perform(withJson(put("/api/v1/bookings/" + bookingId), bob, json.writeValueAsString(upd))).andExpect(status().isForbidden());
        mvc.perform(withJson(put("/api/v1/bookings/" + bookingId), alice, json.writeValueAsString(upd)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.private").value(false));
        mvc.perform(get("/api/v1/schedule").param("date", day()).header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.rooms[0].bookings[0].title").value(SECRET_TITLE)).andExpect(jsonPath("$.rooms[0].bookings[0].masked").value(false));
        upd.put("private", true);
        mvc.perform(withJson(put("/api/v1/bookings/" + bookingId), alice, json.writeValueAsString(upd))).andExpect(status().isOk());
        assertNoLeak(body(mvc.perform(get("/api/v1/schedule").param("date", day()).header("Authorization", bearer(bob)))));
    }

    @Test
    void publicBookingsStayVisibleByDefault() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomId", room.getId());
        m.put("title", "Sprint review");
        m.put("startTime", at(1, 14));
        m.put("endTime", at(1, 15));
        m.put("attendees", 4);
        mvc.perform(withJson(post("/api/v1/bookings"), alice, json.writeValueAsString(m)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.private").value(false));
        String schedule = body(mvc.perform(get("/api/v1/schedule").param("date", day()).header("Authorization", bearer(bob))));
        assertThat(schedule).contains("Sprint review").contains("alice");
    }

    @Test
    void everyOccurrenceOfAPrivateSeriesIsMasked_andHidesTheSeriesToo() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomId", room.getId());
        m.put("title", "Weekly HR confidential");
        m.put("startTime", at(2, 9));
        m.put("endTime", at(2, 10));
        m.put("attendees", 3);
        m.put("frequency", "WEEKLY");
        m.put("occurrences", 3);
        m.put("private", true);
        String created = body(mvc.perform(withJson(post("/api/v1/bookings/series"), alice, json.writeValueAsString(m))).andExpect(status().isCreated()));
        String seriesId = json.readTree(created).get("seriesId").asText();
        String schedule = body(mvc.perform(get("/api/v1/rooms/" + room.getId() + "/schedule")
                .param("from", at(1, 0)).param("to", at(30, 0)).header("Authorization", bearer(bob))));
        assertThat(schedule).doesNotContain("HR confidential").doesNotContain(seriesId).doesNotContain("seriesId");
    }

    @Test
    void roomBlocksAreNeverMasked() throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("roomId", room.getId());
        b.put("startTime", at(3, 9));
        b.put("endTime", at(3, 12));
        b.put("reason", "Projector repair");
        mvc.perform(withJson(post("/api/v1/admin/blocks"), admin, json.writeValueAsString(b))).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/schedule").param("date", LocalDate.now(zone).plusDays(3).toString()).header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.rooms[0].bookings[0].title").value("Projector repair"))
                .andExpect(jsonPath("$.rooms[0].bookings[0].masked").value(false));
    }
}

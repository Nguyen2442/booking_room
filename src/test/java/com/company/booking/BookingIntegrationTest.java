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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class BookingIntegrationTest extends IntegrationTestBase {

    private User admin;
    private User alice;
    private User bob;
    private Room room;

    @BeforeEach
    void data() {
        admin = user("admin@company.local", Role.ADMIN);
        alice = user("alice@company.local", Role.USER);
        bob = user("bob@company.local", Role.USER);
        room = room("Everest", 10);
    }

    private ResultActions book(User who, Room r, String start, String end) throws Exception {
        return mvc.perform(withJson(post("/api/v1/bookings"), who, bookingJson(r, start, end)));
    }

    private String create(User who, String start, String end) throws Exception {
        String body = book(who, room, start, end).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    @Test
    void createReturnsBookingInCompanyTimezone() throws Exception {
        book(alice, room, at(1, 10), at(1, 11)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.booker.fullName").value("alice"))
                .andExpect(jsonPath("$.booker.email").doesNotExist())
                .andExpect(jsonPath("$.startTime").value(org.hamcrest.Matchers.endsWith("+07:00")));
    }

    @Test
    void overlappingBookingIsRejected() throws Exception {
        create(alice, at(1, 10), at(1, 12));
        book(bob, room, at(1, 11), at(1, 13)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_CONFLICT"));
        book(bob, room, at(1, 9), at(1, 10, 15)).andExpect(status().isConflict());   // overlaps the first 15 minutes
        book(bob, room, at(1, 10, 30), at(1, 11)).andExpect(status().isConflict());  // fully inside
        book(bob, room, at(1, 9), at(1, 13)).andExpect(status().isConflict());       // fully surrounding
        book(bob, room, at(1, 10), at(1, 12)).andExpect(status().isConflict());      // identical
    }

    @Test
    void backToBackBookingsAreAllowed() throws Exception {
        create(alice, at(1, 10), at(1, 11));
        book(bob, room, at(1, 11), at(1, 12)).andExpect(status().isCreated());
        book(bob, room, at(1, 9), at(1, 10)).andExpect(status().isCreated());
    }

    @Test
    void sameTimeInDifferentRoomsIsFine() throws Exception {
        Room other = room("K2", 6);
        create(alice, at(1, 10), at(1, 11));
        book(bob, other, at(1, 10), at(1, 11)).andExpect(status().isCreated());
    }

    @Test
    void businessRulesAreReportedWithStableCodes() throws Exception {
        book(alice, room, at(-1, 10), at(-1, 11)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IN_THE_PAST"));
        book(alice, room, at(1, 10, 5), at(1, 11)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TIME_NOT_ALIGNED"));
        book(alice, room, at(1, 6), at(1, 7)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OUTSIDE_WORKING_HOURS"));
        book(alice, room, at(1, 9), at(1, 15)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DURATION_TOO_LONG"));
        book(alice, room, at(1, 11), at(1, 10)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        book(alice, room, at(200, 10), at(200, 11)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TOO_FAR_IN_ADVANCE"));
        book(alice, room, at(31, 10), at(31, 11)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TOO_FAR_IN_ADVANCE"));
        book(alice, room, at(29, 10), at(29, 11)).andExpect(status().isCreated());
    }

    @Test
    void malformedInputIsA400() throws Exception {
        mvc.perform(withJson(post("/api/v1/bookings"), alice, "{\"roomId\":\"" + room.getId() + "\",\"title\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(withJson(post("/api/v1/bookings"), alice,
                "{\"roomId\":\"" + room.getId() + "\",\"title\":\"x\",\"startTime\":\"yesterday\",\"endTime\":\"tomorrow\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrInactiveRoom() throws Exception {
        mvc.perform(withJson(post("/api/v1/bookings"), alice,
                "{\"roomId\":\"" + java.util.UUID.randomUUID() + "\",\"title\":\"x\",\"attendees\":2,\"startTime\":\"" + at(1, 10) + "\",\"endTime\":\"" + at(1, 11) + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
        room.setActive(false);
        rooms.save(room);
        book(alice, room, at(1, 10), at(1, 11)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ROOM_INACTIVE"));
    }

    @Test
    void disabledUserWithStillValidTokenCannotBook() throws Exception {
        String token = bearer(alice);
        alice.setActive(false);
        users.save(alice);
        mvc.perform(post("/api/v1/bookings").header("Authorization", token).contentType("application/json")
                        .content(bookingJson(room, at(1, 10), at(1, 11))))
                // revoked at the door: the token no longer proves anything about a disabled account
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void ownerCancelsAndSlotBecomesFreeAgain() throws Exception {
        String id = create(alice, at(1, 10), at(1, 11));
        mvc.perform(withJson(post("/api/v1/bookings/" + id + "/cancel"), alice, "{\"reason\":\"plans changed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("plans changed"));
        book(bob, room, at(1, 10), at(1, 11)).andExpect(status().isCreated());
    }

    @Test
    void cancelWithoutBodyWorksAndDoubleCancelIsAConflict() throws Exception {
        String id = create(alice, at(1, 10), at(1, 11));
        mvc.perform(post("/api/v1/bookings/" + id + "/cancel").header("Authorization", bearer(alice))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/bookings/" + id + "/cancel").header("Authorization", bearer(alice)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_ALREADY_CANCELLED"));
    }

    @Test
    void otherUsersCannotTouchMyBooking_butAdminCan() throws Exception {
        String id = create(alice, at(1, 10), at(1, 11));
        mvc.perform(get("/api/v1/bookings/" + id).header("Authorization", bearer(bob))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bookings/" + id + "/cancel").header("Authorization", bearer(bob))).andExpect(status().isForbidden());
        mvc.perform(withJson(put("/api/v1/bookings/" + id), bob, updateBody("Hijack", at(1, 10), at(1, 11)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/bookings/" + id).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/bookings/" + id + "/cancel").header("Authorization", bearer(admin))).andExpect(status().isOk());
    }

    private String updateBody(String title, String start, String end) throws Exception {
        return json.writeValueAsString(java.util.Map.of("title", title, "startTime", start, "endTime", end, "attendees", 2));
    }

    @Test
    void updateMayOverlapItselfButNotOthers() throws Exception {
        String id = create(alice, at(1, 10), at(1, 11));
        create(bob, at(1, 12), at(1, 13));
        mvc.perform(withJson(put("/api/v1/bookings/" + id), alice, updateBody("Longer", at(1, 10), at(1, 12))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Longer"));
        mvc.perform(withJson(put("/api/v1/bookings/" + id), alice, updateBody("Too long", at(1, 10), at(1, 13))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_CONFLICT"));
    }

    @Test
    void cancelledBookingCannotBeUpdated() throws Exception {
        String id = create(alice, at(1, 10), at(1, 11));
        mvc.perform(post("/api/v1/bookings/" + id + "/cancel").header("Authorization", bearer(alice))).andExpect(status().isOk());
        mvc.perform(withJson(put("/api/v1/bookings/" + id), alice, updateBody("x", at(1, 14), at(1, 15))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_NOT_ACTIVE"));
    }

    @Test
    void myBookingsListsOnlyMineWithFilters() throws Exception {
        String a1 = create(alice, at(1, 10), at(1, 11));
        create(alice, at(2, 10), at(2, 11));
        create(bob, at(1, 12), at(1, 13));
        mvc.perform(post("/api/v1/bookings/" + a1 + "/cancel").header("Authorization", bearer(alice)));
        mvc.perform(get("/api/v1/bookings/my").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/bookings/my?status=CONFIRMED").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/bookings/my").param("from", at(2, 0)).param("to", at(3, 0)).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/bookings/my").param("from", at(3, 0)).param("to", at(2, 0)).header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }

    @Test
    void endedBeforeSeparatesFinishedFromOngoingMeetings() throws Exception {
        java.time.Instant now = java.time.Instant.now();
        saveBooking(alice, now.minusSeconds(7200), now.minusSeconds(3600)); // finished
        saveBooking(alice, now.minusSeconds(1800), now.plusSeconds(1800));  // in progress
        create(alice, at(1, 10), at(1, 11));                                 // upcoming
        mvc.perform(get("/api/v1/bookings/my").param("endedBefore", now.toString()).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/bookings/my").param("from", now.toString()).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(2)); // in progress + upcoming
    }

    private void saveBooking(User who, java.time.Instant s, java.time.Instant e) {
        var b = new com.company.booking.booking.Booking();
        b.setRoom(room);
        b.setUser(who);
        b.setTitle("seeded");
        b.setStartTime(s);
        b.setEndTime(e);
        bookingRepo.save(b);
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.company.booking.booking.BookingRepository bookingRepo;

    @Test
    void adminListsEverythingUsersCannot() throws Exception {
        create(alice, at(1, 10), at(1, 11));
        create(bob, at(1, 12), at(1, 13));
        mvc.perform(get("/api/v1/bookings").header("Authorization", bearer(admin))).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/bookings?userId=" + bob.getId()).header("Authorization", bearer(admin))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/bookings").header("Authorization", bearer(alice))).andExpect(status().isForbidden());
    }

    @Test
    void deactivatingUserCancelsTheirUpcomingBookings() throws Exception {
        String id = create(bob, at(1, 10), at(1, 11));
        String body = "{\"fullName\":\"bob\",\"role\":\"USER\",\"active\":false}";
        mvc.perform(withJson(put("/api/v1/admin/users/" + bob.getId()), admin, body)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings/" + id).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancelReason").value("User account deactivated"));
        book(alice, room, at(1, 10), at(1, 11)).andExpect(status().isCreated());
    }

    @Test
    void requestIdIsEchoedAndSanitized() throws Exception {
        mvc.perform(get("/api/v1/rooms").header("Authorization", bearer(alice)).header("X-Request-Id", "abc-123"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Request-Id", "abc-123"));
        String evil = mvc.perform(get("/api/v1/rooms").header("Authorization", bearer(alice)).header("X-Request-Id", "bad id\\nINJECT"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(evil).doesNotContain("INJECT").hasSize(36);
    }
}

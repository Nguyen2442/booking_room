package com.company.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.booking.room.Room;
import com.company.booking.user.Role;
import com.company.booking.user.User;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AvailabilityIntegrationTest extends IntegrationTestBase {

    private User alice;
    private Room big;
    private Room small;

    @BeforeEach
    void data() {
        alice = user("alice@company.local", Role.USER);
        big = room("Big", 20, "PROJECTOR");
        small = room("Small", 4);
    }

    private void book(Room r, int day, int from, int to) throws Exception {
        mvc.perform(withJson(post("/api/v1/bookings"), alice, bookingJson(r, at(day, from), at(day, to)))).andExpect(status().isCreated());
    }

    private String day(int ahead) {
        return LocalDate.now(zone).plusDays(ahead).toString();
    }

    @Test
    void availableRoomsExcludeBusyOnes() throws Exception {
        book(big, 1, 10, 12);
        mvc.perform(get("/api/v1/rooms/available").param("start", at(1, 11)).param("end", at(1, 13)).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].name").value("Small"));
        // back-to-back: the big room is free from 12:00
        mvc.perform(get("/api/v1/rooms/available").param("start", at(1, 12)).param("end", at(1, 13)).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void availableRoomsAreOrderedBySmallestSufficientCapacity() throws Exception {
        mvc.perform(get("/api/v1/rooms/available").param("start", at(1, 10)).param("end", at(1, 11)).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.content[0].name").value("Small")).andExpect(jsonPath("$.content[1].name").value("Big"));
    }

    @Test
    void availableRoomsFilterCapacityAndEquipment() throws Exception {
        mvc.perform(get("/api/v1/rooms/available").param("start", at(1, 10)).param("end", at(1, 11)).param("minCapacity", "10")
                        .header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].name").value("Big"));
        mvc.perform(get("/api/v1/rooms/available").param("start", at(1, 10)).param("end", at(1, 11)).param("equipment", "projector")
                        .header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void availableRoomsIgnoreCancelledAndInactive() throws Exception {
        small.setActive(false);
        rooms.save(small);
        mvc.perform(get("/api/v1/rooms/available").param("start", at(1, 10)).param("end", at(1, 11)).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchAppliesBookingTimeRules() throws Exception {
        mvc.perform(get("/api/v1/rooms/available").param("start", at(1, 6)).param("end", at(1, 7)).header("Authorization", bearer(alice)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OUTSIDE_WORKING_HOURS"));
        mvc.perform(get("/api/v1/rooms/available").param("start", "garbage").param("end", at(1, 7)).header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rooms/available").header("Authorization", bearer(alice))).andExpect(status().isBadRequest());
    }

    @Test
    void unencodedPlusInOffsetIsTolerated() throws Exception {
        // curl "...?start=2030-01-01T10:00:00+07:00" arrives as "...T10:00:00 07:00" after URL decoding
        String start = at(1, 10).replace("+", " ");
        String end = at(1, 11).replace("+", " ");
        mvc.perform(get("/api/v1/rooms/available").param("start", start).param("end", end).header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
    }

    @Test
    void roomScheduleByDateIsSortedAndHidesCancelled() throws Exception {
        book(big, 1, 14, 15);
        book(big, 1, 9, 10);
        book(big, 2, 9, 10); // another day
        mvc.perform(get("/api/v1/rooms/" + big.getId() + "/schedule").param("date", day(1)).header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookings.length()").value(2))
                .andExpect(jsonPath("$.bookings[0].title").value("Meeting"))
                .andExpect(jsonPath("$.room.name").value("Big"));
        mvc.perform(get("/api/v1/rooms/" + big.getId() + "/schedule").param("from", at(1, 0)).param("to", at(3, 0)).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.bookings.length()").value(3));
    }

    @Test
    void scheduleRangeValidation() throws Exception {
        mvc.perform(get("/api/v1/rooms/" + big.getId() + "/schedule").header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_RANGE"));
        mvc.perform(get("/api/v1/rooms/" + big.getId() + "/schedule").param("from", at(1, 0)).param("to", at(60, 0)).header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"));
        mvc.perform(get("/api/v1/rooms/" + java.util.UUID.randomUUID() + "/schedule").param("date", day(1)).header("Authorization", bearer(alice)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/rooms/" + big.getId() + "/schedule").param("date", "31-12-2030").header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void daySchedulePerRoomIncludingEmptyOnes() throws Exception {
        book(big, 1, 10, 11);
        mvc.perform(get("/api/v1/schedule").param("date", day(1)).header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rooms.length()").value(2))
                .andExpect(jsonPath("$.rooms[0].room.name").value("Big")).andExpect(jsonPath("$.rooms[0].bookings.length()").value(1))
                .andExpect(jsonPath("$.rooms[1].bookings.length()").value(0));
    }

    @Test
    void freeSlotsAreTheGapsInsideWorkingHours() throws Exception {
        book(big, 1, 10, 12);
        book(big, 1, 14, 15);
        mvc.perform(get("/api/v1/rooms/" + big.getId() + "/free-slots").param("date", day(1)).header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].minutes").value(120)) // 08-10
                .andExpect(jsonPath("$[1].minutes").value(120)) // 12-14
                .andExpect(jsonPath("$[2].minutes").value(300)); // 15-20
        mvc.perform(get("/api/v1/rooms/" + big.getId() + "/free-slots").param("date", day(1)).param("minDuration", "180")
                        .header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void freeSlotsInThePastAreEmpty() throws Exception {
        mvc.perform(get("/api/v1/rooms/" + big.getId() + "/free-slots").param("date", day(-1)).header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}

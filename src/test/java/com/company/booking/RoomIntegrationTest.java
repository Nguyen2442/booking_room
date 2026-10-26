package com.company.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

class RoomIntegrationTest extends IntegrationTestBase {

    private User admin;
    private User alice;

    @BeforeEach
    void users() {
        admin = user("admin@company.local", Role.ADMIN);
        alice = user("alice@company.local", Role.USER);
    }

    private static String roomBody(String name, int capacity, String equipment) {
        return "{\"name\":\"" + name + "\",\"location\":\"Floor 1\",\"capacity\":" + capacity
                + ",\"equipment\":[" + equipment + "],\"description\":\"d\"}";
    }

    @Test
    void adminCreatesRoomAndEquipmentIsNormalized() throws Exception {
        mvc.perform(withJson(post("/api/v1/rooms"), admin, roomBody("Everest", 20, "\"projector\",\"Projector\",\" tv \"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipment.length()").value(2))
                .andExpect(jsonPath("$.equipment[0]").value("PROJECTOR"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void regularUserCannotMutateRooms() throws Exception {
        Room r = room("Fuji", 8);
        mvc.perform(withJson(post("/api/v1/rooms"), alice, roomBody("X", 5, ""))).andExpect(status().isForbidden());
        mvc.perform(withJson(put("/api/v1/rooms/" + r.getId()), alice, roomBody("Fuji", 5, ""))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/rooms/" + r.getId()).header("Authorization", bearer(alice))).andExpect(status().isForbidden());
    }

    @Test
    void validationErrors() throws Exception {
        mvc.perform(withJson(post("/api/v1/rooms"), admin, roomBody("", 0, "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(withJson(post("/api/v1/rooms"), admin, "{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists());
    }

    @Test
    void roomNameMustBeUniqueIgnoringCase() throws Exception {
        room("Everest", 20);
        mvc.perform(withJson(post("/api/v1/rooms"), admin, roomBody("everest", 5, "")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_NAME_EXISTS"));
    }

    @Test
    void updateCanKeepOwnNameButNotTakeAnothers() throws Exception {
        Room a = room("A", 5);
        room("B", 5);
        mvc.perform(withJson(put("/api/v1/rooms/" + a.getId()), admin, roomBody("A", 9, ""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(9));
        mvc.perform(withJson(put("/api/v1/rooms/" + a.getId()), admin, roomBody("b", 9, ""))).andExpect(status().isConflict());
    }

    @Test
    void listFiltersByCapacityAndEquipment() throws Exception {
        room("Small", 4, "TV");
        room("Medium", 10, "TV", "PROJECTOR");
        room("Big", 30, "PROJECTOR", "VIDEO_CONFERENCE");
        mvc.perform(get("/api/v1/rooms?minCapacity=10").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/rooms?equipment=tv&equipment=projector").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].name").value("Medium"));
        mvc.perform(get("/api/v1/rooms?equipment=projector,video_conference").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].name").value("Big"));
    }

    @Test
    void paginationAndSortWhitelist() throws Exception {
        for (int i = 0; i < 5; i++) {
            room("Room" + i, 5 + i);
        }
        mvc.perform(get("/api/v1/rooms?size=2&page=1&sort=capacity,desc").header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].capacity").value(7));
        mvc.perform(get("/api/v1/rooms?sort=equipment").header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SORT"));
        mvc.perform(get("/api/v1/rooms?size=100000").header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100)); // capped
    }

    @Test
    void invalidParameterTypesReturn400() throws Exception {
        mvc.perform(get("/api/v1/rooms?minCapacity=abc").header("Authorization", bearer(alice))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rooms?minCapacity=0").header("Authorization", bearer(alice))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rooms/not-a-uuid").header("Authorization", bearer(alice))).andExpect(status().isBadRequest());
    }

    @Test
    void deactivatedRoomsAreHiddenFromUsersButVisibleToAdmin() throws Exception {
        Room r = room("Old", 5);
        mvc.perform(delete("/api/v1/rooms/" + r.getId()).header("Authorization", bearer(admin))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/rooms/" + r.getId()).header("Authorization", bearer(admin))).andExpect(status().isNoContent()); // idempotent
        mvc.perform(get("/api/v1/rooms").header("Authorization", bearer(alice))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/rooms/" + r.getId()).header("Authorization", bearer(alice))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/rooms?active=false").header("Authorization", bearer(admin))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(post("/api/v1/rooms/" + r.getId() + "/activate").header("Authorization", bearer(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/rooms").header("Authorization", bearer(alice))).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void cannotDeactivateRoomWithUpcomingBookings() throws Exception {
        Room r = room("Busy", 5);
        mvc.perform(withJson(post("/api/v1/bookings"), alice, bookingJson(r, at(1, 10), at(1, 11)))).andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/rooms/" + r.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_HAS_FUTURE_BOOKINGS"));
    }

    @Test
    void unknownRoomIs404() throws Exception {
        mvc.perform(get("/api/v1/rooms/" + java.util.UUID.randomUUID()).header("Authorization", bearer(alice)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }
}

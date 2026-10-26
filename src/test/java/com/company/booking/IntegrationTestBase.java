package com.company.booking;

import com.company.booking.common.AppProperties;
import com.company.booking.room.Room;
import com.company.booking.room.RoomRepository;
import com.company.booking.security.JwtService;
import com.company.booking.user.Role;
import com.company.booking.user.User;
import com.company.booking.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Full application against a real PostgreSQL (see docker-compose.test.yml). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class IntegrationTestBase {

    public static final String PASSWORD = "Password123!";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository users;
    @Autowired protected RoomRepository rooms;
    @Autowired protected JwtService jwt;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected AppProperties props;
    @Autowired protected MutableClock clock;

    protected ZoneId zone;

    @BeforeEach
    void cleanDatabase() {
        zone = props.rules().timezone();
        clock.reset();
        jdbc.execute("TRUNCATE bookings, room_equipment, rooms, users CASCADE");
    }

    protected User user(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setFullName(email.substring(0, email.indexOf('@')));
        u.setRole(role);
        u.setPasswordHash(encoder.encode(PASSWORD));
        return users.save(u);
    }

    protected Room room(String name, int capacity, String... equipment) {
        Room r = new Room();
        r.setName(name);
        r.setCapacity(capacity);
        r.setEquipment(new HashSet<>(java.util.List.of(equipment)));
        return rooms.save(r);
    }

    protected String bearer(User u) {
        return "Bearer " + jwt.issue(u);
    }

    /** Local time in company zone, {@code daysAhead} days from today. */
    protected String at(int daysAhead, int hour, int minute) {
        return LocalDate.now(zone).plusDays(daysAhead).atTime(hour, minute).atZone(zone).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    protected String at(int daysAhead, int hour) {
        return at(daysAhead, hour, 0);
    }

    protected String bookingJson(Room room, String start, String end) throws Exception {
        return json.writeValueAsString(Map.of("roomId", room.getId(), "title", "Meeting", "startTime", start, "endTime", end, "attendees", 2));
    }

    protected MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder b, User who, String body) {
        return b.header("Authorization", bearer(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Pins "now" to a wall-clock time today in the company zone, e.g. pinNow(10, 20) -> 10:20. */
    protected void pinNow(int hour, int minute) {
        clock.set(LocalDate.now(zone).atTime(hour, minute).atZone(zone).toInstant());
    }

    protected Instant instant(String iso) {
        return java.time.OffsetDateTime.parse(iso).toInstant();
    }
}

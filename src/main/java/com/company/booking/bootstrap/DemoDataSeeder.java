package com.company.booking.bootstrap;

import com.company.booking.booking.Booking;
import com.company.booking.booking.BookingRepository;
import com.company.booking.common.AppProperties;
import com.company.booking.room.Room;
import com.company.booking.room.RoomRepository;
import com.company.booking.user.Role;
import com.company.booking.user.User;
import com.company.booking.user.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Profile "demo" only: 10 rooms, 5 employees (password "Password123!") and a few bookings for tomorrow. */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private final RoomRepository rooms;
    private final UserRepository users;
    private final BookingRepository bookings;
    private final PasswordEncoder encoder;
    private final AppProperties props;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (rooms.count() > 0) {
            return;
        }
        List<Room> created = List.of(
                room("Everest", "Floor 1", 20, "PROJECTOR", "VIDEO_CONFERENCE", "WHITEBOARD"),
                room("K2", "Floor 1", 12, "PROJECTOR", "WHITEBOARD"),
                room("Fuji", "Floor 2", 8, "TV", "WHITEBOARD"),
                room("Alpine", "Floor 2", 6, "TV"),
                room("Sakura", "Floor 2", 4),
                room("Mekong", "Floor 3", 10, "PROJECTOR", "VIDEO_CONFERENCE"),
                room("Halong", "Floor 3", 6, "WHITEBOARD"),
                room("Sapa", "Floor 3", 4, "TV"),
                room("Hue", "Floor 4", 15, "PROJECTOR", "WHITEBOARD"),
                room("Saigon", "Floor 4", 30, "PROJECTOR", "VIDEO_CONFERENCE", "WHITEBOARD"));
        rooms.saveAll(created);

        List<User> employees = List.of(
                user("alice@company.local", "Alice Nguyen"), user("bob@company.local", "Bob Tran"),
                user("carol@company.local", "Carol Le"), user("dave@company.local", "Dave Pham"),
                user("eve@company.local", "Eve Vo"));
        users.saveAll(employees);

        ZoneId zone = props.rules().timezone();
        LocalDate tomorrow = LocalDate.now(clock.withZone(zone)).plusDays(1);
        book(created.get(0), employees.get(0), tomorrow, 9, 10, "Weekly planning");
        book(created.get(0), employees.get(1), tomorrow, 10, 12, "Sprint review");
        book(created.get(1), employees.get(2), tomorrow, 14, 15, "Design sync");
        book(created.get(2), employees.get(3), tomorrow, 9, 11, "1:1 interviews");
        log.info("Demo data seeded: {} rooms, {} users", created.size(), employees.size());
    }

    private Room room(String name, String location, int capacity, String... equipment) {
        Room r = new Room();
        r.setName(name);
        r.setLocation(location);
        r.setCapacity(capacity);
        r.setEquipment(new java.util.HashSet<>(Set.of(equipment)));
        return r;
    }

    private User user(String email, String name) {
        User u = new User();
        u.setEmail(email);
        u.setFullName(name);
        u.setRole(Role.USER);
        u.setPasswordHash(encoder.encode("Password123!"));
        return u;
    }

    private void book(Room room, User user, LocalDate day, int startHour, int endHour, String title) {
        ZoneId zone = props.rules().timezone();
        Booking b = new Booking();
        b.setRoom(room);
        b.setUser(user);
        b.setTitle(title);
        b.setStartTime(day.atTime(startHour, 0).atZone(zone).toInstant());
        b.setEndTime(day.atTime(endHour, 0).atZone(zone).toInstant());
        bookings.save(b);
    }
}

package com.company.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.booking.booking.BookingDtos.CreateBookingRequest;
import com.company.booking.booking.BookingService;
import com.company.booking.common.ApiException;
import com.company.booking.room.Room;
import com.company.booking.user.Role;
import com.company.booking.user.User;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The core guarantee: no matter how many requests race, a slot is given out exactly once. */
class BookingConcurrencyTest extends IntegrationTestBase {

    @Autowired BookingService service;

    @Test
    void manyUsersRacingForTheSameSlotYieldExactlyOneBooking() throws Exception {
        Room room = room("Everest", 10);
        int threads = 20;
        List<User> people = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            people.add(user("u" + i + "@company.local", Role.USER));
        }
        CreateBookingRequest req = new CreateBookingRequest(room.getId(), "Race", null,
                OffsetDateTime.parse(at(1, 10)), OffsetDateTime.parse(at(1, 11)), 2, null);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();
        runConcurrently(people, u -> {
            try {
                service.create(u.getId(), req);
                ok.incrementAndGet();
            } catch (ApiException e) {
                if ("BOOKING_CONFLICT".equals(e.getCode())) {
                    conflicts.incrementAndGet();
                } else {
                    synchronized (unexpected) { unexpected.add(e); }
                }
            } catch (Throwable t) {
                synchronized (unexpected) { unexpected.add(t); }
            }
        });

        assertThat(unexpected).isEmpty();
        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("select count(*) from bookings where status='CONFIRMED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void racingOverlappingButDifferentWindowsNeverOverlap() throws Exception {
        Room room = room("Everest", 10);
        List<User> people = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            people.add(user("p" + i + "@company.local", Role.USER));
        }
        AtomicInteger idx = new AtomicInteger();
        runConcurrently(people, u -> {
            int i = idx.getAndIncrement();
            // windows of 2h starting every 30min: heavily overlapping neighbours
            int startMinutes = 8 * 60 + (i % 8) * 30;
            OffsetDateTime s = OffsetDateTime.parse(at(1, startMinutes / 60, startMinutes % 60));
            try {
                service.create(u.getId(), new CreateBookingRequest(room.getId(), "x", null, s, s.plusHours(2), 2, null));
            } catch (ApiException ignored) {
                // conflicts are expected
            }
        });
        Integer overlaps = jdbc.queryForObject("""
                select count(*) from bookings a join bookings b
                  on a.room_id = b.room_id and a.id < b.id and a.time_range && b.time_range
                where a.status = 'CONFIRMED' and b.status = 'CONFIRMED'""", Integer.class);
        assertThat(overlaps).isZero();
    }

    @Test
    void deactivationRacingWithBookingsNeverLeavesBookingsInAClosedRoom() throws Exception {
        Room room = room("Closing", 10);
        User admin = user("admin@company.local", Role.ADMIN);
        List<User> people = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            people.add(user("q" + i + "@company.local", Role.USER));
        }
        AtomicInteger idx = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (User u : people) {
            tasks.add(() -> {
                int i = idx.getAndIncrement();
                OffsetDateTime s = OffsetDateTime.parse(at(1, 8 + i));
                try {
                    service.create(u.getId(), new CreateBookingRequest(room.getId(), "x", null, s, s.plusHours(1), 2, null));
                } catch (ApiException ignored) { }
                return null;
            });
        }
        tasks.add(() -> {
            try {
                roomService().deactivate(room.getId());
            } catch (ApiException ignored) { }
            return null;
        });
        runAll(tasks);
        Integer inactiveWithBookings = jdbc.queryForObject("""
                select count(*) from rooms r where not r.active
                  and exists (select 1 from bookings b where b.room_id = r.id and b.status = 'CONFIRMED')""", Integer.class);
        assertThat(inactiveWithBookings).isZero();
    }

    @Autowired com.company.booking.room.RoomService roomService;

    private com.company.booking.room.RoomService roomService() {
        return roomService;
    }

    private void runConcurrently(List<User> people, java.util.function.Consumer<User> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (User u : people) {
            tasks.add(() -> {
                start.await();
                action.accept(u);
                return null;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(people.size());
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> t : tasks) {
                futures.add(pool.submit(t));
            }
            start.countDown(); // release every thread at once
            for (Future<Void> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void runAll(List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}

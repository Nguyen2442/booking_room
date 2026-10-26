package com.company.booking.booking;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class BookingSpecifications {

    private BookingSpecifications() {}

    /** Bookings overlapping [from, to) when either bound is given. */
    public static Specification<Booking> filter(
            UUID roomId, UUID userId, BookingStatus status, Instant from, Instant to, Instant endedBefore,
            BookingKind kind) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (roomId != null) {
                p.add(cb.equal(root.get("room").get("id"), roomId));
            }
            if (userId != null) {
                p.add(cb.equal(root.get("user").get("id"), userId));
            }
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                p.add(cb.greaterThan(root.get("endTime"), from));
            }
            if (to != null) {
                p.add(cb.lessThan(root.get("startTime"), to));
            }
            if (kind != null) {
                p.add(cb.equal(root.get("kind"), kind));
            }
            if (endedBefore != null) {
                p.add(cb.lessThanOrEqualTo(root.get("endTime"), endedBefore));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}

package com.company.booking.room;

import com.company.booking.booking.Booking;
import com.company.booking.booking.BookingStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class RoomSpecifications {

    private RoomSpecifications() {}

    public static Specification<Room> filter(Integer minCapacity, Set<String> equipment, Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (minCapacity != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("capacity"), minCapacity));
            }
            if (equipment != null) {
                // every requested item must be present (AND semantics)
                for (String e : equipment) {
                    p.add(cb.isMember(e, root.<Set<String>>get("equipment")));
                }
            }
            if (active != null) {
                p.add(cb.equal(root.get("active"), active));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    /** Active rooms with no CONFIRMED booking overlapping [start, end). */
    public static Specification<Room> availableBetween(
            Instant start, Instant end, Integer minCapacity, Set<String> equipment) {
        return filter(minCapacity, equipment, true).and((root, query, cb) -> {
            Subquery<UUID> sq = query.subquery(UUID.class);
            Root<Booking> b = sq.from(Booking.class);
            sq.select(b.get("id"))
                    .where(cb.equal(b.get("room"), root),
                            cb.equal(b.get("status"), BookingStatus.CONFIRMED),
                            cb.lessThan(b.get("startTime"), end),
                            cb.greaterThan(b.get("endTime"), start));
            return cb.not(cb.exists(sq));
        });
    }
}

package com.company.booking.booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    @Override
    @EntityGraph(attributePaths = {"room", "user"})
    Page<Booking> findAll(Specification<Booking> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"room", "user"})
    Optional<Booking> findWithDetailsById(UUID id);

    @Query("""
            select count(b) > 0 from Booking b
            where b.room.id = :roomId and b.status = com.company.booking.booking.BookingStatus.CONFIRMED
              and b.startTime < :end and b.endTime > :start""")
    boolean existsConflict(@Param("roomId") UUID roomId, @Param("start") Instant start, @Param("end") Instant end);

    @Query("""
            select count(b) > 0 from Booking b
            where b.room.id = :roomId and b.status = com.company.booking.booking.BookingStatus.CONFIRMED
              and b.startTime < :end and b.endTime > :start and b.id <> :excludeId""")
    boolean existsConflictExcluding(@Param("roomId") UUID roomId, @Param("start") Instant start,
                                    @Param("end") Instant end, @Param("excludeId") UUID excludeId);

    /** Upcoming bookings of a person that start inside the booking window (these are what the cap counts). */
    @Query("""
            select count(b) from Booking b
            where b.user.id = :userId and b.status = com.company.booking.booking.BookingStatus.CONFIRMED
              and b.kind = com.company.booking.booking.BookingKind.BOOKING
              and b.endTime > :now and b.startTime < :windowEnd and b.id <> :excludeId""")
    long countActiveInWindow(@Param("userId") UUID userId, @Param("now") Instant now,
                             @Param("windowEnd") Instant windowEnd, @Param("excludeId") UUID excludeId);

    /** Minutes a person already has booked that start in [from, to): the basis of the per-day limit. */
    @Query(value = """
            select coalesce(sum(extract(epoch from (end_time - start_time)) / 60), 0)
            from bookings
            where user_id = :userId and kind = 'BOOKING' and status = 'CONFIRMED'
              and start_time >= :from and start_time < :to and id <> :excludeId""", nativeQuery = true)
    double sumBookedMinutes(@Param("userId") UUID userId, @Param("from") Instant from,
                            @Param("to") Instant to, @Param("excludeId") UUID excludeId);

    @Query("""
            select count(b) > 0 from Booking b
            where b.room.id = :roomId and b.status = com.company.booking.booking.BookingStatus.CONFIRMED
              and b.kind = com.company.booking.booking.BookingKind.BOOKING
              and b.endTime > :now and b.attendees > :capacity""")
    boolean existsUpcomingOverCapacity(@Param("roomId") UUID roomId, @Param("now") Instant now, @Param("capacity") int capacity);

    @Query("""
            select count(b) > 0 from Booking b
            where b.room.id = :roomId and b.status = com.company.booking.booking.BookingStatus.CONFIRMED
              and b.kind = com.company.booking.booking.BookingKind.BOOKING
              and b.endTime > :now""")
    boolean existsFutureConfirmed(@Param("roomId") UUID roomId, @Param("now") Instant now);

    @Query("""
            select b from Booking b join fetch b.user
            where b.room.id = :roomId and b.status = com.company.booking.booking.BookingStatus.CONFIRMED
              and b.startTime < :to and b.endTime > :from
            order by b.startTime""")
    List<Booking> findRoomSchedule(@Param("roomId") UUID roomId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select b from Booking b join fetch b.room join fetch b.user
            where b.status = com.company.booking.booking.BookingStatus.CONFIRMED
              and b.startTime < :to and b.endTime > :from
            order by b.startTime""")
    List<Booking> findSchedule(@Param("from") Instant from, @Param("to") Instant to);

    /** Bulk cancel used when an account is deactivated. Bumps version so concurrent edits are detected. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Booking b set b.status = com.company.booking.booking.BookingStatus.CANCELLED,
                b.cancelledAt = :now, b.cancelledBy = :by, b.cancelReason = :reason,
                b.updatedAt = :now, b.version = b.version + 1
            where b.user.id = :userId and b.status = com.company.booking.booking.BookingStatus.CONFIRMED
              and b.kind = com.company.booking.booking.BookingKind.BOOKING
              and b.endTime > :now""")
    int cancelFutureByUser(@Param("userId") UUID userId, @Param("now") Instant now,
                           @Param("by") UUID by, @Param("reason") String reason);

    @Query("""
            select b from Booking b join fetch b.user join fetch b.room
            where b.room.id = :roomId and b.status = com.company.booking.booking.BookingStatus.CONFIRMED
              and b.startTime < :end and b.endTime > :start
            order by b.startTime""")
    List<Booking> findConfirmedOverlapping(@Param("roomId") UUID roomId, @Param("start") Instant start, @Param("end") Instant end);

    /** Upcoming, still-active occurrences of a series from {@code from} onwards. */
    @Query("""
            select b from Booking b join fetch b.user join fetch b.room
            where b.seriesId = :seriesId and b.status = com.company.booking.booking.BookingStatus.CONFIRMED
              and b.startTime >= :from and b.endTime > :now
            order by b.startTime""")
    List<Booking> findActiveSeriesFrom(@Param("seriesId") UUID seriesId, @Param("from") Instant from, @Param("now") Instant now);
}

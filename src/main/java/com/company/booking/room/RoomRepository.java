package com.company.booking.room;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, UUID>, JpaSpecificationExecutor<Room> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    /**
     * SELECT ... FOR SHARE. Many bookings can be created for the room concurrently, but a concurrent
     * deactivation (which takes FOR UPDATE) has to wait, so no booking slips in for a room that is being closed.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForShare(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") UUID id);
}

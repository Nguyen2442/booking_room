package com.company.booking.user;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByRoleAndActive(Role role, boolean active);

    /**
     * SELECT ... FOR UPDATE on the user row. Taken before checking per-user quotas so two parallel requests of the
     * same person cannot both slip under a limit. Always locked before the room row (consistent order, no deadlock).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    interface Status {
        boolean getActive();

        Role getRole();

        int getTokenVersion();
    }

    /** Light-weight lookup used to validate a token against the current state of the account. */
    @Query("select u.active as active, u.role as role, u.tokenVersion as tokenVersion from User u where u.id = :id")
    Optional<Status> findStatusById(@Param("id") UUID id);
}

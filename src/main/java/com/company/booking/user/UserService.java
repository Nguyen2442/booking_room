package com.company.booking.user;

import com.company.booking.booking.BookingRepository;
import com.company.booking.common.AuditLog;
import com.company.booking.security.UserStatusCache;
import com.company.booking.common.ApiException;
import com.company.booking.common.PageResponse;
import com.company.booking.common.SortGuard;
import com.company.booking.user.UserDtos.CreateUserRequest;
import com.company.booking.user.UserDtos.UpdateUserRequest;
import com.company.booking.user.UserDtos.UserResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Set<String> SORTABLE = Set.of("email", "fullName", "role", "active", "createdAt");

    private final UserRepository users;
    private final BookingRepository bookings;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final UserStatusCache statusCache;

    @PersistenceContext
    private EntityManager em;

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public UserResponse create(CreateUserRequest req) {
        String email = normalizeEmail(req.email());
        if (users.existsByEmail(email)) {
            throw emailTaken();
        }
        PasswordPolicy.check(req.password());
        User u = new User();
        u.setEmail(email);
        u.setFullName(req.fullName().trim());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(req.role());
        try {
            User saved = users.saveAndFlush(u);
            AuditLog.record("user.create", Map.of("user", saved.getEmail(), "role", saved.getRole()));
            return UserResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // lost a race with a concurrent create of the same email
            throw emailTaken();
        }
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(String q, Role role, Boolean active, Pageable pageable) {
        SortGuard.check(pageable, SORTABLE);
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String term = q.trim().toLowerCase(Locale.ROOT);
                String like = "%" + escapeLike(term.length() > 100 ? term.substring(0, 100) : term) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("email")), like, '\\'),
                        cb.like(cb.lower(root.get("fullName")), like, '\\')));
            }
            if (role != null) {
                p.add(cb.equal(root.get("role"), role));
            }
            if (active != null) {
                p.add(cb.equal(root.get("active"), active));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return PageResponse.from(users.findAll(spec, pageable).map(UserResponse::from));
    }

    @Transactional
    public UserResponse update(UUID actorId, UUID id, UpdateUserRequest req) {
        User u = find(id);
        boolean deactivating = u.isActive() && !req.active();
        if (id.equals(actorId) && (deactivating || req.role() != Role.ADMIN)) {
            // Prevents the last admin from locking everybody out by accident.
            throw ApiException.unprocessable("CANNOT_MODIFY_SELF", "You cannot deactivate or demote your own account");
        }
        boolean losingAdmin = u.getRole() == Role.ADMIN && u.isActive() && (req.role() != Role.ADMIN || !req.active());
        if (losingAdmin) {
            // Two admins demoting each other at the same moment would leave nobody in charge: serialize and re-count.
            em.createNativeQuery("select 1 from (select pg_advisory_xact_lock(4242)) t").getSingleResult();
            if (users.countByRoleAndActive(Role.ADMIN, true) < 2) {
                throw ApiException.unprocessable("LAST_ADMIN", "There must always be at least one active administrator");
            }
        }
        boolean privilegeChange = deactivating || u.getRole() != req.role();
        u.setFullName(req.fullName().trim());
        u.setRole(req.role());
        u.setActive(req.active());
        if (privilegeChange) {
            u.setTokenVersion(u.getTokenVersion() + 1); // signs the user out everywhere
        }
        if (deactivating) {
            bookings.cancelFutureByUser(id, clock.instant(), actorId, "User account deactivated");
        }
        User saved = users.saveAndFlush(u);
        statusCache.invalidateAfterCommit(id);
        AuditLog.record("user.update", Map.of("user", saved.getEmail(), "role", saved.getRole(), "active", saved.isActive()));
        return UserResponse.from(saved);
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        PasswordPolicy.check(newPassword);
        User u = find(id);
        u.setPasswordHash(encoder.encode(newPassword));
        u.setTokenVersion(u.getTokenVersion() + 1); // whoever held the old password is signed out
        users.save(u);
        statusCache.invalidateAfterCommit(id);
        AuditLog.record("user.reset-password", Map.of("user", u.getEmail()));
    }

    private User find(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
    }

    private static ApiException emailTaken() {
        return ApiException.conflict("EMAIL_ALREADY_EXISTS", "A user with this email already exists");
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

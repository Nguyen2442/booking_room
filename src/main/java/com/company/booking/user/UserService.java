package com.company.booking.user;

import com.company.booking.booking.BookingRepository;
import com.company.booking.common.ApiException;
import com.company.booking.common.PageResponse;
import com.company.booking.common.SortGuard;
import com.company.booking.user.UserDtos.CreateUserRequest;
import com.company.booking.user.UserDtos.UpdateUserRequest;
import com.company.booking.user.UserDtos.UserResponse;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
            return UserResponse.from(users.saveAndFlush(u));
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
                String like = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
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
        u.setFullName(req.fullName().trim());
        u.setRole(req.role());
        u.setActive(req.active());
        if (deactivating) {
            bookings.cancelFutureByUser(id, clock.instant(), actorId, "User account deactivated");
        }
        return UserResponse.from(users.saveAndFlush(u));
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        PasswordPolicy.check(newPassword);
        User u = find(id);
        u.setPasswordHash(encoder.encode(newPassword));
        users.save(u);
    }

    @Transactional
    public void changePassword(UUID id, String current, String newPassword) {
        PasswordPolicy.check(newPassword);
        User u = find(id);
        if (!encoder.matches(current, u.getPasswordHash())) {
            throw ApiException.unprocessable("INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        u.setPasswordHash(encoder.encode(newPassword));
        users.save(u);
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

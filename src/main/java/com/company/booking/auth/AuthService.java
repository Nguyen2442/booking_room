package com.company.booking.auth;

import com.company.booking.auth.AuthDtos.TokenResponse;
import com.company.booking.common.ApiException;
import com.company.booking.common.AuditLog;
import com.company.booking.security.UserStatusCache;
import com.company.booking.user.PasswordPolicy;
import java.util.Map;
import com.company.booking.security.JwtService;
import com.company.booking.security.LoginAttemptService;
import com.company.booking.user.User;
import com.company.booking.user.UserDtos.UserResponse;
import com.company.booking.user.UserRepository;
import com.company.booking.user.UserService;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final LoginAttemptService attempts;
    private final UserStatusCache statusCache;
    /** Compared against when the email is unknown so response time does not reveal which emails exist. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, LoginAttemptService attempts,
                       UserStatusCache statusCache) {
        this.statusCache = statusCache;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.attempts = attempts;
        this.dummyHash = encoder.encode("timing-equalizer");
    }

    @Transactional(readOnly = true)
    public TokenResponse login(String rawEmail, String password, String clientIp) {
        String email = UserService.normalizeEmail(rawEmail);
        String key = clientIp + "|" + email;
        if (attempts.isBlocked(key)) {
            throw ApiException.tooManyRequests("TOO_MANY_ATTEMPTS", "Too many failed logins, try again later");
        }
        User user = users.findByEmail(email).orElse(null);
        boolean passwordOk = encoder.matches(password, user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !passwordOk || !user.isActive()) {
            attempts.recordFailure(key);
            org.slf4j.LoggerFactory.getLogger("AUDIT").warn("action=login.failed email={} ip={}",
                    AuditLog.clean(email), AuditLog.clean(clientIp));
            // same message for unknown user / wrong password / disabled account
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
        }
        attempts.recordSuccess(key);
        return new TokenResponse(jwt.issue(user), "Bearer", jwt.ttl().toSeconds(), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        User user = users.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Account no longer exists or is disabled"));
        return UserResponse.from(user);
    }

    /**
     * Changing the password signs the account out everywhere (token version bump), so the response carries a fresh
     * token for the caller. The current password is throttled like a login: a stolen token must not be a way to
     * guess it.
     */
    @Transactional
    public TokenResponse changePassword(UUID userId, String current, String newPassword) {
        String key = "pw|" + userId;
        if (attempts.isBlocked(key)) {
            throw ApiException.tooManyRequests("TOO_MANY_ATTEMPTS", "Too many failed attempts, try again later");
        }
        PasswordPolicy.check(newPassword);
        User user = users.findById(userId).filter(User::isActive)
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Account no longer exists or is disabled"));
        if (!encoder.matches(current, user.getPasswordHash())) {
            attempts.recordFailure(key);
            throw ApiException.unprocessable("INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        attempts.recordSuccess(key);
        user.setPasswordHash(encoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.saveAndFlush(user);
        statusCache.invalidateAfterCommit(userId);
        AuditLog.record("user.change-password", Map.of("user", user.getEmail()));
        return new TokenResponse(jwt.issue(user), "Bearer", jwt.ttl().toSeconds(), UserResponse.from(user));
    }
}

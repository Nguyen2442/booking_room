package com.company.booking.auth;

import com.company.booking.auth.AuthDtos.TokenResponse;
import com.company.booking.common.ApiException;
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
    /** Compared against when the email is unknown so response time does not reveal which emails exist. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, LoginAttemptService attempts) {
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
}

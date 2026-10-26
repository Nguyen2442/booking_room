package com.company.booking.security;

import com.company.booking.user.Role;
import com.company.booking.user.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Checks a token against the *current* state of the account, so deactivating a user, changing a password or
 * changing a role takes effect immediately instead of when the JWT happens to expire. One primary-key lookup per
 * user per 30 seconds per instance; changes made on the same instance invalidate the entry at once.
 */
@Component
public class UserStatusCache {

    private record Status(boolean active, Role role, int tokenVersion) {}

    private final Cache<UUID, Status> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(50_000)
            .build();
    private final UserRepository users;

    public UserStatusCache(UserRepository users) {
        this.users = users;
    }

    /** The authenticated principal with its *current* role, or empty if the account is gone, disabled or revoked. */
    public Optional<AuthUser> verify(JwtService.ParsedToken token) {
        UUID id = token.user().id();
        Status s = cache.getIfPresent(id);
        if (s == null || s.tokenVersion() != token.version()) {
            // miss, or the cached version is older than the token (changed on another instance): ask the database
            s = load(id);
        }
        if (s == null || !s.active() || s.tokenVersion() != token.version()) {
            return Optional.empty();
        }
        return Optional.of(new AuthUser(id, token.user().email(), s.role()));
    }

    private Status load(UUID id) {
        Status s = users.findStatusById(id).map(v -> new Status(v.getActive(), v.getRole(), v.getTokenVersion())).orElse(null);
        if (s != null) {
            cache.put(id, s);
        }
        return s;
    }

    public void invalidate(UUID id) {
        cache.invalidate(id);
    }

    /** Evict once the change is visible to other connections; evicting earlier could re-cache the old state. */
    public void invalidateAfterCommit(UUID id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate(id);
                }
            });
        } else {
            invalidate(id);
        }
    }
}

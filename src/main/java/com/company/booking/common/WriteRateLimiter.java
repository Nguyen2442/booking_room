package com.company.booking.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Caps how many booking changes one person can fire per minute: a script (or a hijacked session) cannot flood the
 * system, while a human clicking around never notices. Per instance, like the login throttle; a gateway can add a
 * shared limit in front when running many replicas.
 */
@Component
public class WriteRateLimiter {

    private final Cache<UUID, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(100_000)
            .build();
    private final int limit;

    public WriteRateLimiter(AppProperties props) {
        this.limit = props.rateLimit().writesPerMinute();
    }

    public boolean tryAcquire(UUID user) {
        return counters.get(user, k -> new AtomicInteger()).incrementAndGet() <= limit;
    }
}

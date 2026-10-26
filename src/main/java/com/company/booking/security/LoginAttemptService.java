package com.company.booking.security;

import com.company.booking.common.AppProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Per (ip, email) brute-force throttle kept in memory. It is per instance by design: with many replicas
 * the effective limit is N x max-failures, which still stops online guessing. A shared limiter
 * (gateway / Redis) is the documented next step.
 */
@Component
public class LoginAttemptService {

    private final Cache<String, AtomicInteger> failures;
    private final int maxFailures;

    public LoginAttemptService(AppProperties props) {
        this.maxFailures = props.loginProtection().maxFailures();
        this.failures = Caffeine.newBuilder()
                .expireAfterWrite(props.loginProtection().lockDuration())
                .maximumSize(100_000) // bounded: an attacker cannot exhaust memory with random emails
                .build();
    }

    public boolean isBlocked(String key) {
        AtomicInteger c = failures.getIfPresent(key);
        return c != null && c.get() >= maxFailures;
    }

    public void recordFailure(String key) {
        failures.asMap().computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    public void recordSuccess(String key) {
        failures.invalidate(key);
    }
}

package com.company.booking.common;

import com.company.booking.security.AuthUser;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Applies {@link WriteRateLimiter} to state-changing booking requests (reads are never limited here). */
@Component
public class WriteRateLimitInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITES = Set.of("POST", "PUT", "DELETE", "PATCH");
    private final WriteRateLimiter limiter;
    private final MeterRegistry metrics;

    public WriteRateLimitInterceptor(WriteRateLimiter limiter, MeterRegistry metrics) {
        this.limiter = limiter;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!WRITES.contains(request.getMethod())) {
            return true;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user && !limiter.tryAcquire(user.id())) {
            metrics.counter("booking.rejected", "reason", "rate-limit").increment();
            response.setHeader("Retry-After", "60");
            throw ApiException.tooManyRequests("RATE_LIMITED", "Too many changes in a short time, please slow down");
        }
        return true;
    }
}

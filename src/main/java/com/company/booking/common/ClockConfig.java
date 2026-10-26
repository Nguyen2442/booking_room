package com.company.booking.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injected everywhere instead of Instant.now() so time-dependent rules are testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

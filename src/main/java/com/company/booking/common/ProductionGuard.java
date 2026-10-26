package com.company.booking.common;

import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to start in the "prod" profile with the example secrets from docker-compose / .env.example. Forgetting to
 * change them is the most common way a "production" system ends up with a publicly known admin password.
 */
@Component
public class ProductionGuard {

    private static final List<String> PLACEHOLDERS = List.of("dev-only", "change-me", "changeme", "generate-with");

    public ProductionGuard(Environment env, AppProperties props) {
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            verify(props, env.getProperty("spring.datasource.password", ""), env.acceptsProfiles(Profiles.of("demo")));
        }
    }

    static void verify(AppProperties props, String dbPassword, boolean demoProfileActive) {
        if (demoProfileActive) {
            throw new IllegalStateException("The 'demo' profile (sample users with known passwords) must not run together with 'prod'");
        }
        if (looksLikePlaceholder(props.jwt().secret())) {
            throw new IllegalStateException("JWT_SECRET is still an example value: set a random one (openssl rand -base64 48)");
        }
        AppProperties.Bootstrap b = props.bootstrap();
        if (b.enabled() && (looksLikePlaceholder(b.adminPassword()) || "Admin12345!".equals(b.adminPassword()))) {
            throw new IllegalStateException("ADMIN_PASSWORD is still an example value: set a strong one");
        }
        if ("booking".equals(dbPassword) || looksLikePlaceholder(dbPassword)) {
            throw new IllegalStateException("DB_PASSWORD is still an example value: set a strong one");
        }
    }

    private static boolean looksLikePlaceholder(String value) {
        if (value == null) {
            return false;
        }
        String v = value.toLowerCase();
        return PLACEHOLDERS.stream().anyMatch(v::contains);
    }
}

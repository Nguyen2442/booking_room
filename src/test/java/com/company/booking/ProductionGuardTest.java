package com.company.booking;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.booking.common.AppProperties;
import com.company.booking.common.ProductionGuard;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionGuardTest {

    private static AppProperties props(String secret, String adminPassword) {
        return new AppProperties(
                new AppProperties.Jwt(secret, Duration.ofHours(8), "t"),
                new AppProperties.Rules(ZoneId.of("UTC"), LocalTime.of(8, 0), LocalTime.of(20, 0), 15, 15, 240, 30, 90, 20, 480, 15),
                new AppProperties.Bootstrap("admin@x.com", adminPassword, "Admin"),
                new AppProperties.LoginProtection(5, Duration.ofMinutes(15)), new AppProperties.RateLimit(30), null);
    }

    private static final String GOOD_SECRET = "k8Zq1x0m4Pq7Vn2Rb6Tc9Yd3Wf5Hg8Js1Lk4Mn7Op0";

    @Test
    void aProperlyConfiguredProductionStarts() {
        assertThatCode(() -> new ProductionGuard(new MockEnvironment().withProperty("spring.profiles.active", "prod")
                .withProperty("spring.datasource.password", "s3cure-db-pass"), props(GOOD_SECRET, "An0ther-Str0ng-One")))
                .doesNotThrowAnyException();
    }

    @Test
    void exampleSecretsAreRefusedInProduction() {
        MockEnvironment prod = new MockEnvironment().withProperty("spring.datasource.password", "s3cure-db-pass");
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new ProductionGuard(prod, props("dev-only-secret-change-me-dev-only-secret-change-me", "An0ther-Str0ng-One")))
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new ProductionGuard(prod, props(GOOD_SECRET, "Admin12345!"))).hasMessageContaining("ADMIN_PASSWORD");
        MockEnvironment weakDb = new MockEnvironment().withProperty("spring.datasource.password", "booking");
        weakDb.setActiveProfiles("prod");
        assertThatThrownBy(() -> new ProductionGuard(weakDb, props(GOOD_SECRET, "An0ther-Str0ng-One"))).hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void theDemoProfileCannotRunAsProduction() {
        MockEnvironment both = new MockEnvironment().withProperty("spring.datasource.password", "s3cure-db-pass");
        both.setActiveProfiles("prod", "demo");
        assertThatThrownBy(() -> new ProductionGuard(both, props(GOOD_SECRET, "An0ther-Str0ng-One"))).hasMessageContaining("demo");
    }

    @Test
    void developmentAndDemoAreNotRestricted() {
        assertThatCode(() -> new ProductionGuard(new MockEnvironment(), props("dev-only-secret-change-me-dev-only-secret-change-me", "Admin12345!")))
                .doesNotThrowAnyException();
    }
}

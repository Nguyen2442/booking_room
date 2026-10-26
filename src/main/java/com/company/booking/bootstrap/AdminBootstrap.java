package com.company.booking.bootstrap;

import com.company.booking.common.AppProperties;
import com.company.booking.user.PasswordPolicy;
import com.company.booking.user.Role;
import com.company.booking.user.User;
import com.company.booking.user.UserRepository;
import com.company.booking.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first admin from ADMIN_EMAIL / ADMIN_PASSWORD if it does not exist yet (idempotent, replica-safe). */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final AppProperties props;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Bootstrap b = props.bootstrap();
        if (!b.enabled()) {
            return;
        }
        String email = UserService.normalizeEmail(b.adminEmail());
        if (users.existsByEmail(email)) {
            return;
        }
        PasswordPolicy.check(b.adminPassword());
        User admin = new User();
        admin.setEmail(email);
        admin.setFullName(b.adminName() == null || b.adminName().isBlank() ? "Administrator" : b.adminName());
        admin.setPasswordHash(encoder.encode(b.adminPassword()));
        admin.setRole(Role.ADMIN);
        try {
            users.saveAndFlush(admin);
            log.info("Bootstrap admin created: {}", email);
        } catch (DataIntegrityViolationException e) {
            log.info("Bootstrap admin was created concurrently by another instance");
        }
    }
}

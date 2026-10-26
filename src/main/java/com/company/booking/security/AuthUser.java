package com.company.booking.security;

import com.company.booking.user.Role;
import java.util.UUID;

/** Authenticated principal rebuilt from JWT claims; no database hit per request. */
public record AuthUser(UUID id, String email, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}

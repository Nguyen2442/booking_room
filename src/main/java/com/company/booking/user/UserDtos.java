package com.company.booking.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class UserDtos {

    private UserDtos() {}

    public record UserResponse(UUID id, String email, String fullName, Role role, boolean active, Instant createdAt) {
        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.isActive(), u.getCreatedAt());
        }
    }

    /** BCrypt only uses the first 72 bytes, so longer passwords are rejected rather than silently truncated. */
    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 150) String fullName,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotNull Role role) {}

    public record UpdateUserRequest(
            @NotBlank @Size(max = 150) String fullName, @NotNull Role role, @NotNull Boolean active) {}

    public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 72) String newPassword) {}
}

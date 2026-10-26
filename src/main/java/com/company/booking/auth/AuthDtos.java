package com.company.booking.auth;

import com.company.booking.user.UserDtos.UserResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(@NotBlank @Size(max = 255) String email, @NotBlank @Size(max = 200) String password) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {}

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 200) String currentPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) {}
}

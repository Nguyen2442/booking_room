package com.company.booking.auth;

import com.company.booking.auth.AuthDtos.ChangePasswordRequest;
import com.company.booking.auth.AuthDtos.LoginRequest;
import com.company.booking.auth.AuthDtos.TokenResponse;
import com.company.booking.security.AuthUser;
import com.company.booking.user.UserDtos.UserResponse;
import com.company.booking.user.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth")
public class AuthController {

    private final AuthService auth;
    private final UserService users;

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        // remoteAddr is already the real client IP when behind a trusted proxy (forward-headers-strategy=framework)
        return auth.login(req.email(), req.password(), http.getRemoteAddr());
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthUser user) {
        return auth.me(user.id());
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AuthUser user, @Valid @RequestBody ChangePasswordRequest req) {
        users.changePassword(user.id(), req.currentPassword(), req.newPassword());
        return ResponseEntity.noContent().build();
    }
}

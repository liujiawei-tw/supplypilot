package com.supplypilot.security;

import com.supplypilot.user.dto.request.LoginRequest;
import com.supplypilot.user.dto.response.AuthenticatedUserResponse;
import com.supplypilot.user.dto.response.LoginResponse;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import java.time.Clock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing authentication endpoints:
 * <ul>
 *   <li>POST /api/v1/auth/login</li>
 *   <li>GET /api/v1/auth/me</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AuthController(AuthService authService, PasswordEncoder passwordEncoder, Clock clock) {
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public AuthenticatedUserResponse getCurrentUser(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.getCurrentUser(principal);
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthenticatedUser principal,
                               @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(principal, request, passwordEncoder, clock);
    }
}

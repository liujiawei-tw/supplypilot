package com.supplypilot.security;

import com.supplypilot.user.dto.request.LoginRequest;
import com.supplypilot.user.dto.response.AuthenticatedUserResponse;
import com.supplypilot.user.dto.response.LoginResponse;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public AuthenticatedUserResponse getCurrentUser(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.getCurrentUser(principal);
    }
}

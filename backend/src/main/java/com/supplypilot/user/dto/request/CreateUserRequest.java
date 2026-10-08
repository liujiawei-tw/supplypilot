package com.supplypilot.user.dto.request;

import com.supplypilot.user.domain.UserRole;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "Username is required.")
        @Size(max = 50, message = "Username must not exceed 50 characters.")
        String username,

        @NotBlank(message = "Password is required.")
        @Size(min = 6, max = 100, message = "Password must be between 6 and 100 characters.")
        String password,

        @NotBlank(message = "Display name is required.")
        @Size(max = 100, message = "Display name must not exceed 100 characters.")
        String displayName,

        @NotNull(message = "Role is required.")
        UserRole role
) {
}

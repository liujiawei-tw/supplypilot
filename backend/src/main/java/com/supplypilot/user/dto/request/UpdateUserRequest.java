package com.supplypilot.user.dto.request;

import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(max = 100, message = "Display name must not exceed 100 characters.")
        String displayName,

        UserRole role,

        UserStatus status
) {
}

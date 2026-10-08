package com.supplypilot.user.dto.response;

import java.time.Instant;

import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;

public record UserResponse(
        Long id,
        String username,
        String displayName,
        UserRole role,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}

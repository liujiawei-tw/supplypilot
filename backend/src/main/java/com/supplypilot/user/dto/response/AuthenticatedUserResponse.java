package com.supplypilot.user.dto.response;

import com.supplypilot.user.domain.UserRole;

public record AuthenticatedUserResponse(
        Long id,
        String username,
        String displayName,
        UserRole role
) {
}

package com.supplypilot.user.dto.response;

import com.supplypilot.user.domain.UserRole;

public record AuthenticatedUserResponse(
        Long id,
        String username,
        String displayName,
        UserRole role,
        boolean canManageUsers,
        boolean mustChangePassword
) {
    public AuthenticatedUserResponse(Long id, String username, String displayName, UserRole role) {
        this(id, username, displayName, role, false, false);
    }

    public AuthenticatedUserResponse(Long id, String username, String displayName, UserRole role,
                                     boolean mustChangePassword) {
        this(id, username, displayName, role, false, mustChangePassword);
    }
}

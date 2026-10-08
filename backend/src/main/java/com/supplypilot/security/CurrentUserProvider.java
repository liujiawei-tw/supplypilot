package com.supplypilot.security;

import com.supplypilot.user.domain.UserRole;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Trusted boundary for the identity used by application services and future audit fields.
 *
 * <p>Identity is read only from an authenticated {@link AuthenticatedUser} principal in the
 * {@link org.springframework.security.core.context.SecurityContext}; request payload values are
 * deliberately never considered.
 */
@Component
public class CurrentUserProvider {

    public Long getCurrentUserId() {
        return currentUser().getId();
    }

    public String getCurrentUsername() {
        return currentUser().getUsername();
    }

    public UserRole getCurrentRole() {
        return currentUser().getRole();
    }

    private AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof AuthenticatedUser authenticatedUser)) {
            throw new AuthenticationCredentialsNotFoundException("An authenticated user is required.");
        }
        return authenticatedUser;
    }
}

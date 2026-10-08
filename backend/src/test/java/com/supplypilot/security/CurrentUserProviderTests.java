package com.supplypilot.security;

import com.supplypilot.user.domain.UserRole;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserProviderTests {

    private final CurrentUserProvider currentUserProvider = new CurrentUserProvider();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsIdUsernameAndRoleFromAuthenticatedUserOnly() {
        AuthenticatedUser principal = new AuthenticatedUser(42L, "trusted-user", UserRole.PROCUREMENT_MANAGER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()
        ));

        assertThat(currentUserProvider.getCurrentUserId()).isEqualTo(42L);
        assertThat(currentUserProvider.getCurrentUsername()).isEqualTo("trusted-user");
        assertThat(currentUserProvider.getCurrentRole()).isEqualTo(UserRole.PROCUREMENT_MANAGER);
    }

    @Test
    void rejectsMissingAuthentication() {
        assertThatThrownBy(currentUserProvider::getCurrentUserId)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void rejectsAnonymousAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", java.util.List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        ));

        assertThatThrownBy(currentUserProvider::getCurrentUsername)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void rejectsUnexpectedPrincipalInsteadOfTrustingClientSuppliedIdentity() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "client-supplied-admin-id", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));

        assertThatThrownBy(currentUserProvider::getCurrentRole)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void doesNotLeakIdentityBetweenSecurityContexts() {
        AuthenticatedUser first = new AuthenticatedUser(1L, "first", UserRole.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                first, null, first.getAuthorities()
        ));
        assertThat(currentUserProvider.getCurrentUserId()).isEqualTo(1L);

        SecurityContextHolder.clearContext();
        AuthenticatedUser second = new AuthenticatedUser(2L, "second", UserRole.WAREHOUSE_STAFF);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                second, null, second.getAuthorities()
        ));
        assertThat(currentUserProvider.getCurrentUserId()).isEqualTo(2L);
    }
}

package com.supplypilot.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.supplypilot.user.domain.SecurityUser;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

class DemoBaselineCredentialAuthenticatorTests {
    private UserRepository users;
    private DemoHostCredentialVerifier verifier;
    private DemoBaselineCredentialAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        verifier = mock(DemoHostCredentialVerifier.class);
        authenticator = new DemoBaselineCredentialAuthenticator(users, verifier);
    }

    @Test
    void synchronouslyVerifiesOnlyActiveBaselineAccountsThroughHostAuthority() {
        when(users.findByUsername("admin")).thenReturn(java.util.Optional.of(activeUser()));
        when(verifier.verify("admin", "Demo123456")).thenReturn(true);
        Authentication result = authenticator.authenticate("admin", "Demo123456");
        assertInstanceOf(SecurityUser.class, result.getPrincipal());
        verify(verifier).verify("admin", "Demo123456");
    }

    @Test
    void hostDenialOrFailureNeverFallsBackToDatabaseHash() {
        when(users.findByUsername("buyer")).thenReturn(java.util.Optional.of(activeUser("buyer")));
        when(verifier.verify("buyer", "Demo123456")).thenReturn(false);
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("buyer", "Demo123456"));
        when(verifier.verify("buyer", "Demo123456")).thenThrow(new BadCredentialsException("Host unavailable"));
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("buyer", "Demo123456"));
        verify(verifier, times(2)).verify("buyer", "Demo123456");
    }

    @Test
    void inactiveOrUnknownUsersNeverReachHostVerifier() {
        User inactive = User.create("manager", "unused-hash", "Manager", UserRole.ADMIN, Instant.now());
        inactive.inactivate(Instant.now());
        when(users.findByUsername("manager")).thenReturn(java.util.Optional.of(inactive));
        when(users.findByUsername("planner")).thenReturn(java.util.Optional.empty());
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("manager", "Demo123456"));
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("planner", "Demo123456"));
        verifyNoInteractions(verifier);
    }

    @Test
    void onlyNamedBaselineAccountsUseHostVerifier() {
        for (String username : new String[]{"admin", "manager", "buyer", "warehouse", "planner"}) {
            assertTrue(authenticator.handles(username));
            assertTrue(authenticator.handles(username.toUpperCase(java.util.Locale.ROOT)));
        }
        assertFalse(authenticator.handles("custom-user"));
    }

    private User activeUser() { return activeUser("admin"); }

    private User activeUser(String username) {
        return User.create(username, "unused-hash", username, UserRole.ADMIN, Instant.now());
    }
}

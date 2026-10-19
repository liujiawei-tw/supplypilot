package com.supplypilot.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.supplypilot.user.domain.SecurityUser;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

class DemoBaselineCredentialAuthenticatorTests {
    private static final String PASSWORD = "Demo123456";
    private UserRepository users;
    private DemoBaselineCredentialAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        authenticator = new DemoBaselineCredentialAuthenticator(users, PASSWORD);
    }

    @Test
    void acceptsConfiguredPasswordForActiveBaselineUserAndUsesDatabaseRole() {
        when(users.findByUsername("manager")).thenReturn(Optional.of(activeUser("manager", UserRole.PROCUREMENT_MANAGER)));

        Authentication result = authenticator.authenticate("manager", PASSWORD);

        assertInstanceOf(SecurityUser.class, result.getPrincipal());
        assertEquals(UserRole.PROCUREMENT_MANAGER, ((SecurityUser) result.getPrincipal()).getRole());
        verify(users).findByUsername("manager");
    }

    @Test
    void rejectsWrongOrNullPassword() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(activeUser("admin", UserRole.ADMIN)));

        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("admin", "Wrong123456"));
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("admin", null));
    }

    @Test
    void rejectsInactiveOrUnknownBaselineUsers() {
        User inactive = activeUser("manager", UserRole.ADMIN);
        inactive.inactivate(Instant.now());
        when(users.findByUsername("manager")).thenReturn(Optional.of(inactive));
        when(users.findByUsername("planner")).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("manager", PASSWORD));
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("planner", PASSWORD));
    }

    @Test
    void onlyFiveNamedAccountsUseDemoPasswordAuthentication() {
        for (String username : new String[]{"admin", "manager", "buyer", "warehouse", "planner"}) {
            assertTrue(authenticator.handles(username));
            assertTrue(authenticator.handles(username.toUpperCase(java.util.Locale.ROOT)));
        }
        assertFalse(authenticator.handles("custom-user"));
        assertFalse(authenticator.handles(null));
    }

    @Test
    void invalidConfiguredPasswordFailsDuringBeanConstruction() {
        assertThrows(IllegalStateException.class, () -> new DemoBaselineCredentialAuthenticator(users, "short"));
        assertThrows(IllegalStateException.class,
                () -> new DemoBaselineCredentialAuthenticator(users, "x".repeat(101)));
        assertThrows(IllegalStateException.class, () -> new DemoBaselineCredentialAuthenticator(users, null));
    }

    @Test
    void recreatingAuthenticatorWithNewConfigurationAcceptsNewAndRejectsOldPassword() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(activeUser("admin", UserRole.ADMIN)));
        DemoBaselineCredentialAuthenticator recreated = new DemoBaselineCredentialAuthenticator(users, "NewDemo123");

        assertThrows(BadCredentialsException.class, () -> recreated.authenticate("admin", PASSWORD));
        assertDoesNotThrow(() -> recreated.authenticate("admin", "NewDemo123"));
    }

    private User activeUser(String username, UserRole role) {
        return User.create(username, "unused-random-hash", username, role, Instant.now());
    }
}

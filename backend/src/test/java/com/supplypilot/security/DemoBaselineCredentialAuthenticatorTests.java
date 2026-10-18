package com.supplypilot.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.supplypilot.user.domain.SecurityUser;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

class DemoBaselineCredentialAuthenticatorTests {
    @TempDir Path tempDir;
    private UserRepository users;
    private Path envFile;
    private DemoBaselineCredentialAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        envFile = tempDir.resolve("password");
        authenticator = new DemoBaselineCredentialAuthenticator(users, envFile.toString());
    }

    @Test
    void rereadsPasswordForEveryBaselineLogin() throws Exception {
        when(users.findByUsername("admin")).thenReturn(java.util.Optional.of(activeUser()));
        Files.writeString(envFile, "Demo123456");
        assertNotNull(authenticator.authenticate("admin", "Demo123456"));

        Files.writeString(envFile, "Demo654321");
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("admin", "Demo123456"));
        Authentication updated = authenticator.authenticate("admin", "Demo654321");
        assertInstanceOf(SecurityUser.class, updated.getPrincipal());
        verify(users, times(3)).findByUsername("admin");
    }

    @Test
    void missingOrMalformedCredentialFileFailsClosed() throws Exception {
        when(users.findByUsername("buyer")).thenReturn(java.util.Optional.of(activeUser()));
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("buyer", "Demo123456"));

        Files.writeString(envFile, "Demo123456\nDemo654321");
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("buyer", "Demo123456"));
        Files.writeString(envFile, "short");
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("buyer", "short"));
    }

    @Test
    void inactiveOrUnknownUsersCannotUseTheDynamicCredential() throws Exception {
        Files.writeString(envFile, "Demo123456");
        User inactive = User.create("manager", "hash", "Manager", UserRole.ADMIN, Instant.now());
        inactive.inactivate(Instant.now());
        when(users.findByUsername("manager")).thenReturn(java.util.Optional.of(inactive));
        when(users.findByUsername("planner")).thenReturn(java.util.Optional.empty());
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("manager", "Demo123456"));
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("planner", "Demo123456"));
    }

    @Test
    void onlyNamedBaselineAccountsUseTheDynamicPath() {
        assertTrue(authenticator.handles("admin"));
        assertTrue(authenticator.handles("Admin"));
        assertTrue(authenticator.handles("planner"));
        assertFalse(authenticator.handles("custom-user"));
    }

    private User activeUser() {
        return User.create("admin", "database-hash-is-not-used", "Admin", UserRole.ADMIN, Instant.now());
    }
}

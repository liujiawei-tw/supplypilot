package com.supplypilot.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.repository.UserRepository;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

class ProductionAdminBootstrapTests {
    private JdbcTemplate jdbc;
    private UserRepository users;
    private BCryptPasswordEncoder encoder;
    private ProductionAdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        users = mock(UserRepository.class);
        encoder = new BCryptPasswordEncoder();
        bootstrap = new ProductionAdminBootstrap(jdbc, users, encoder, Clock.systemUTC(),
                mock(PlatformTransactionManager.class));
    }

    @Test
    void firstRunOnEmptyDatabaseCreatesRestrictedAdminAndPersistentMarker() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(users.count()).thenReturn(0L);
        when(users.existsByCanManageUsersTrueAndStatus(UserStatus.ACTIVE)).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        bootstrap.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(captor.capture());
        User created = captor.getValue();
        assertEquals("admin", created.getUsername());
        assertTrue(encoder.matches("admin", created.getPasswordHash()));
        assertNotEquals("admin", created.getPasswordHash());
        assertEquals(UserRole.ADMIN, created.getRole());
        assertEquals(UserStatus.ACTIVE, created.getStatus());
        assertTrue(created.isMustChangePassword());
        verify(jdbc).update(contains("INSERT IGNORE INTO system_initialization"), eq("production-admin-bootstrap-v1"));
    }

    @Test
    void populatedDatabaseWithActiveAdminIsNeverModified() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(users.count()).thenReturn(4L);
        when(users.existsByCanManageUsersTrueAndStatus(UserStatus.ACTIVE)).thenReturn(true);

        assertDoesNotThrow(() -> bootstrap.run(null));

        verify(users, never()).saveAndFlush(any(User.class));
    }

    @Test
    void existingBootstrapMarkerAndEmptyUserTableCannotRecreateInitialCredentials() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(users.count()).thenReturn(0L);
        when(users.existsByCanManageUsersTrueAndStatus(UserStatus.ACTIVE)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));

        verify(users, never()).saveAndFlush(any(User.class));
    }

    @Test
    void populatedDatabaseWithoutActiveAdminFailsStartupAndDoesNotCreateOne() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(users.count()).thenReturn(2L);
        when(users.existsByCanManageUsersTrueAndStatus(UserStatus.ACTIVE)).thenReturn(false);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        assertTrue(failure.getMessage().contains("no active user manager"));
        verify(users, never()).saveAndFlush(any(User.class));
    }

    @Test
    void existingMarkerWithNoActiveAdminFailsStartup() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(users.count()).thenReturn(3L);
        when(users.existsByCanManageUsersTrueAndStatus(UserStatus.ACTIVE)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        verify(users, never()).saveAndFlush(any(User.class));
    }

    @Test
    void activeAdminBusinessRoleWithoutManagementCapabilityFailsClosed() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(users.count()).thenReturn(2L);
        when(users.existsByCanManageUsersTrueAndStatus(UserStatus.ACTIVE)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        verify(users, never()).saveAndFlush(any(User.class));
        verify(users).existsByCanManageUsersTrueAndStatus(UserStatus.ACTIVE);
        verify(users, never()).existsByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE);
    }
}

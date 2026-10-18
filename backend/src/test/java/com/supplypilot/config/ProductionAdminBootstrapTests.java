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
        when(jdbc.queryForObject(eq("SELECT GET_LOCK(?, 30)"), eq(Integer.class), anyString())).thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT RELEASE_LOCK(?)"), eq(Integer.class), anyString())).thenReturn(1);
    }

    @Test
    void emptyUserTableCreatesOneRestrictedAdminWithEncodedInitialPassword() {
        when(users.count()).thenReturn(0L);
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        bootstrap.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users, times(1)).saveAndFlush(captor.capture());
        User created = captor.getValue();
        assertEquals("admin", created.getUsername());
        assertTrue(encoder.matches("admin", created.getPasswordHash()));
        assertNotEquals("admin", created.getPasswordHash());
        assertEquals(UserRole.ADMIN, created.getRole());
        assertEquals(UserStatus.ACTIVE, created.getStatus());
        assertTrue(created.isMustChangePassword());
    }

    @Test
    void populatedUserTableIsNeverModified() {
        when(users.count()).thenReturn(4L);

        bootstrap.run(null);

        verify(users, never()).saveAndFlush(any(User.class));
        verify(jdbc).queryForObject(eq("SELECT RELEASE_LOCK(?)"), eq(Integer.class), anyString());
    }

    @Test
    void failedConcurrentLockDoesNotCreateAnAdmin() {
        when(jdbc.queryForObject(eq("SELECT GET_LOCK(?, 30)"), eq(Integer.class), anyString())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));

        verify(users, never()).count();
        verify(users, never()).saveAndFlush(any(User.class));
    }
}

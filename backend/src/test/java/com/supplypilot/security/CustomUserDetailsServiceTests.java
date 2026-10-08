package com.supplypilot.security;

import java.time.Instant;
import java.util.Optional;

import com.supplypilot.user.domain.SecurityUser;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTests {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService userDetailsService;

    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    @DisplayName("Loads active user successfully")
    void testLoadActiveUserSuccess() {
        User user = User.create("active_user", "hash123", "Active Display", UserRole.PROCUREMENT_STAFF, NOW);
        when(userRepository.findByUsername("active_user")).thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername("active_user");
        assertThat(userDetails).isInstanceOf(SecurityUser.class);
        assertThat(userDetails.getUsername()).isEqualTo("active_user");
        assertThat(userDetails.getPassword()).isEqualTo("hash123");
        assertThat(userDetails.isEnabled()).isTrue();
        assertThat(userDetails.getAuthorities()).hasSize(1);
        assertThat(userDetails.getAuthorities().iterator().next().getAuthority()).isEqualTo("ROLE_PROCUREMENT_STAFF");
    }

    @Test
    @DisplayName("Loads inactive user with isEnabled=false so Spring Security rejects authentication")
    void testLoadInactiveUserSetsDisabled() {
        User user = User.create("inactive_user", "hash123", "Inactive Display", UserRole.WAREHOUSE_STAFF, NOW);
        user.inactivate(NOW);
        assertThat(user.getStatus()).isEqualTo(UserStatus.INACTIVE);

        when(userRepository.findByUsername("inactive_user")).thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername("inactive_user");
        assertThat(userDetails.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Throws UsernameNotFoundException when user does not exist")
    void testUserNotFoundThrowsException() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("unknown"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("Throws UsernameNotFoundException when username is null or blank")
    void testBlankUsernameThrowsException() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(null))
                .isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(""))
                .isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("   "))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}

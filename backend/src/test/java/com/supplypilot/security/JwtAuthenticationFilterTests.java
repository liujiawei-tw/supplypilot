package com.supplypilot.security;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.repository.UserRepository;
import java.util.Optional;
import jakarta.servlet.ServletException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTests {

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private UserRepository users;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenService, users);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Valid Bearer token populates SecurityContext with AuthenticatedUser")
    void testValidBearerTokenPopulatesSecurityContext() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        AuthenticatedUser tokenUser = new AuthenticatedUser(5L, "buyer01", UserRole.PROCUREMENT_STAFF);
        User current = org.mockito.Mockito.mock(User.class);
        when(jwtTokenService.parseAndValidateToken("valid.jwt.token")).thenReturn(tokenUser);
        when(users.findById(5L)).thenReturn(Optional.of(current));
        when(current.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(current.getUsername()).thenReturn("buyer01");
        when(current.getId()).thenReturn(5L);
        when(current.getRole()).thenReturn(UserRole.PROCUREMENT_STAFF);
        when(current.canManageUsers()).thenReturn(true);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        AuthenticatedUser principal = (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isNotEqualTo(tokenUser);
        assertThat(principal.getRole()).isEqualTo(UserRole.PROCUREMENT_STAFF);
        assertThat(principal.canManageUsers()).isTrue();
        assertThat(principal.getAuthorities()).extracting("authority").contains("USER_MANAGEMENT");
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("A JWT whose role no longer matches its database account is rejected")
    void roleMismatchIsRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid.jwt.token");
        User current = org.mockito.Mockito.mock(User.class);
        when(jwtTokenService.parseAndValidateToken("valid.jwt.token"))
                .thenReturn(new AuthenticatedUser(5L, "buyer01", UserRole.PROCUREMENT_STAFF));
        when(users.findById(5L)).thenReturn(Optional.of(current));
        when(current.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(current.getUsername()).thenReturn("buyer01");
        when(current.getRole()).thenReturn(UserRole.ADMIN);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Disabled, missing, or subject-mismatched accounts do not authenticate an otherwise valid JWT")
    void inactiveOrMismatchedAccountIsRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid.jwt.token");
        User inactive = org.mockito.Mockito.mock(User.class);
        when(jwtTokenService.parseAndValidateToken("valid.jwt.token"))
                .thenReturn(new AuthenticatedUser(5L, "buyer01", UserRole.ADMIN, true, false));
        when(users.findById(5L)).thenReturn(Optional.of(inactive));
        when(inactive.getStatus()).thenReturn(UserStatus.INACTIVE);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Missing Authorization header does not populate SecurityContext and continues chain")
    void testMissingAuthHeaderContinuesChain() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Invalid JWT clears SecurityContext and continues chain")
    void testInvalidJwtClearsSecurityContext() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(jwtTokenService.parseAndValidateToken("invalid.jwt.token"))
                .thenThrow(new JwtAuthenticationException("Invalid token signature"));

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Non-Bearer Authorization header is ignored")
    void testNonBearerHeaderIgnored() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}

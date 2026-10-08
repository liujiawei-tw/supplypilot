package com.supplypilot.integration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private Clock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Success: returns 200, JWT token, Bearer tokenType, and user metadata")
    void testLoginSuccess() throws Exception {
        String username = "auth_test_buyer";
        userRepository.findByUsername(username).ifPresent(userRepository::delete);

        User user = User.create(
                username,
                passwordEncoder.encode("password123"),
                "Procurement Buyer Test",
                UserRole.PROCUREMENT_STAFF,
                clock.instant()
        );
        userRepository.saveAndFlush(user);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "password123"
                                }
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.id").isNumber())
                .andExpect(jsonPath("$.user.username").value(username))
                .andExpect(jsonPath("$.user.displayName").value("Procurement Buyer Test"))
                .andExpect(jsonPath("$.user.role").value("PROCUREMENT_STAFF"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Failure: wrong password returns 401 UNAUTHENTICATED")
    void testLoginFailureWrongPassword() throws Exception {
        String username = "auth_test_wrong_pwd";
        userRepository.findByUsername(username).ifPresent(userRepository::delete);

        User user = User.create(
                username,
                passwordEncoder.encode("correct_pass"),
                "User Name",
                UserRole.PRODUCTION_PLANNER,
                clock.instant()
        );
        userRepository.saveAndFlush(user);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "incorrect_password"
                                }
                                """.formatted(username)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Failure: non-existent username returns 401 UNAUTHENTICATED")
    void testLoginFailureNonExistentUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "totally_non_existent_user_123",
                                  "password": "some_password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Failure: INACTIVE user is rejected at authentication with 401 UNAUTHENTICATED")
    void testLoginFailureInactiveUser() throws Exception {
        String username = "auth_test_inactive";
        userRepository.findByUsername(username).ifPresent(userRepository::delete);

        User user = User.create(
                username,
                passwordEncoder.encode("password123"),
                "Inactive Buyer",
                UserRole.PROCUREMENT_STAFF,
                clock.instant()
        );
        user.inactivate(clock.instant());
        userRepository.saveAndFlush(user);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "password123"
                                }
                                """.formatted(username)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Failure: blank username or password returns 400 VALIDATION_FAILED")
    void testLoginFailureBlankCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "",
                                  "password": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /api/v1/auth/me - Success: returns current user metadata with valid Bearer JWT")
    void testGetMeSuccess() throws Exception {
        String username = "auth_test_me";
        userRepository.findByUsername(username).ifPresent(userRepository::delete);

        User user = User.create(
                username,
                passwordEncoder.encode("password123"),
                "Me Test Display",
                UserRole.ADMIN,
                clock.instant()
        );
        User savedUser = userRepository.saveAndFlush(user);

        JwtTokenService tokenService = new JwtTokenService(jwtProperties, clock);
        String token = tokenService.generateToken(
                new AuthenticatedUser(savedUser.getId(), savedUser.getUsername(), savedUser.getRole())
        );

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(savedUser.getId()))
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.displayName").value("Me Test Display"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/auth/me - Failure: missing token returns 401 UNAUTHENTICATED")
    void testGetMeMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/me"));
    }

    @Test
    @DisplayName("GET /api/v1/auth/me - Failure: expired token returns 401 UNAUTHENTICATED")
    void testGetMeExpiredToken() throws Exception {
        // Generate a token in the past relative to the injected test clock
        Clock pastClock = Clock.fixed(clock.instant().minusSeconds(7200), ZoneOffset.UTC);
        JwtTokenService pastTokenService = new JwtTokenService(jwtProperties, pastClock);
        String expiredToken = pastTokenService.generateToken(
                new AuthenticatedUser(1L, "user", UserRole.ADMIN)
        );

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("GET /api/v1/auth/me - Failure: tampered token returns 401 UNAUTHENTICATED")
    void testGetMeTamperedToken() throws Exception {
        JwtTokenService tokenService = new JwtTokenService(jwtProperties, clock);
        String token = tokenService.generateToken(new AuthenticatedUser(1L, "user", UserRole.ADMIN));
        String tamperedToken = token.substring(0, token.length() - 5) + "abcde";

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("Protected endpoint: GET /api/v1/users without token returns 401 UNAUTHENTICATED")
    void testProtectedUsersEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("CORS preflight for a protected endpoint succeeds without a JWT")
    void testCorsPreflightDoesNotRequireAuthentication() throws Exception {
        mockMvc.perform(options("/api/v1/users")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    @DisplayName("Protected endpoint: GET /api/v1/users with valid token succeeds (200 OK)")
    void testProtectedUsersEndpointWithTokenSucceeds() throws Exception {
        String username = "users_test_auth";
        userRepository.findByUsername(username).ifPresent(userRepository::delete);

        User user = User.create(
                username,
                passwordEncoder.encode("password123"),
                "User List Tester",
                UserRole.ADMIN,
                clock.instant()
        );
        User savedUser = userRepository.saveAndFlush(user);

        JwtTokenService tokenService = new JwtTokenService(jwtProperties, clock);
        String token = tokenService.generateToken(
                new AuthenticatedUser(savedUser.getId(), savedUser.getUsername(), savedUser.getRole())
        );

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("Protected endpoint: GET /api/v1/users with token containing invalid userId returns 401")
    void testProtectedEndpointWithInvalidClaimTokenReturns401() throws Exception {
        byte[] keyBytes = java.util.Base64.getDecoder().decode(jwtProperties.secret());
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(keyBytes);

        // Valid signature, but invalid userId (-5)
        String tokenWithNegativeUserId = io.jsonwebtoken.Jwts.builder()
                .subject("buyer01")
                .claim("userId", -5L)
                .claim("role", "PROCUREMENT_STAFF")
                .issuedAt(java.util.Date.from(clock.instant()))
                .expiration(java.util.Date.from(clock.instant().plusSeconds(3600)))
                .signWith(key, io.jsonwebtoken.Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenWithNegativeUserId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}

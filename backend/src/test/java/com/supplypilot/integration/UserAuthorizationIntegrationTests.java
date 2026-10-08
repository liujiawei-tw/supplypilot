package com.supplypilot.integration;

import java.util.stream.Stream;

import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import com.supplypilot.user.service.UserService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAuthorizationIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private java.time.Clock clock;

    private MockMvc mockMvc;
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        jwtTokenService = new JwtTokenService(jwtProperties, clock);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("User management distinguishes missing and invalid JWT as 401")
    void authenticationFailuresReturnStandard401() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.path").value("/api/v1/users"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer invalid.jwt.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + expiredToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("An authenticated user without ADMIN receives the standard 403 response")
    void authorizationFailureReturnsStandard403() throws Exception {
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearerToken(UserRole.PROCUREMENT_STAFF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this operation."))
                .andExpect(jsonPath("$.path").value("/api/v1/users"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @ParameterizedTest(name = "POST users allows only ADMIN: {0}")
    @MethodSource("allRoles")
    void createUserIsAdminOnly(UserRole role) throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", bearerToken(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"role_create_%s","password":"password123","displayName":"Role Test","role":"WAREHOUSE_STAFF"}
                                """.formatted(role.name().toLowerCase())))
                .andExpect(role == UserRole.ADMIN ? status().isCreated() : status().isForbidden())
                .andExpect(role == UserRole.ADMIN
                        ? jsonPath("$.role").value("WAREHOUSE_STAFF")
                        : jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @ParameterizedTest(name = "GET users allows only ADMIN: {0}")
    @MethodSource("allRoles")
    void listUsersIsAdminOnly(UserRole role) throws Exception {
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearerToken(role)))
                .andExpect(role == UserRole.ADMIN ? status().isOk() : status().isForbidden())
                .andExpect(role == UserRole.ADMIN
                        ? jsonPath("$.content").isArray()
                        : jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @ParameterizedTest(name = "PATCH users allows only ADMIN: {0}")
    @MethodSource("allRoles")
    void updateUserIsAdminOnly(UserRole role) throws Exception {
        Long userId = createUserForUpdate();
        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .header("Authorization", bearerToken(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + "\"displayName\":\"Updated\"}"))
                .andExpect(role == UserRole.ADMIN ? status().isOk() : status().isForbidden())
                .andExpect(role == UserRole.ADMIN
                        ? jsonPath("$.displayName").value("Updated")
                        : jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Authenticated users may read an individual user because this endpoint has no stricter role rule")
    void getUserByIdRemainsAuthenticatedOnly() throws Exception {
        Long userId = createUserForUpdate();
        mockMvc.perform(get("/api/v1/users/{id}", userId)
                        .header("Authorization", bearerToken(UserRole.PRODUCTION_PLANNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId));
    }

    @Test
    @DisplayName("Service proxy prevents direct calls from bypassing ADMIN authorization")
    void directServiceInvocationIsStillAuthorized() {
        AuthenticatedUser staff = new AuthenticatedUser(101L, "staff", UserRole.PROCUREMENT_STAFF);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                staff, null, staff.getAuthorities()
        ));

        assertThatThrownBy(() -> userService.listUsers(org.springframework.data.domain.PageRequest.of(0, 20)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        AuthenticatedUser admin = new AuthenticatedUser(102L, "admin", UserRole.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin, null, admin.getAuthorities()
        ));
        assertThatCode(() -> userService.listUsers(org.springframework.data.domain.PageRequest.of(0, 20)))
                .doesNotThrowAnyException();
    }

    private String bearerToken(UserRole role) {
        return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(
                1000L + role.ordinal(), role.name().toLowerCase(), role
        ));
    }

    private String expiredToken() {
        byte[] keyBytes = java.util.Base64.getDecoder().decode(jwtProperties.secret());
        return io.jsonwebtoken.Jwts.builder()
                .subject("expired-user")
                .claim("userId", 99L)
                .claim("role", UserRole.ADMIN.name())
                .issuedAt(java.util.Date.from(clock.instant().minusSeconds(3600)))
                .expiration(java.util.Date.from(clock.instant().minusSeconds(31)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(keyBytes), io.jsonwebtoken.Jwts.SIG.HS256)
                .compact();
    }

    private Long createUserForUpdate() {
        String username = "target_" + java.util.UUID.randomUUID().toString().replace("-", "");
        return userRepository.saveAndFlush(User.create(
                username, "$2a$10$notARealHashForAuthorizationTests", "Target", UserRole.WAREHOUSE_STAFF,
                java.time.Instant.now()
        )).getId();
    }

    private static Stream<Arguments> allRoles() {
        return Stream.of(UserRole.values()).map(Arguments::of);
    }
}

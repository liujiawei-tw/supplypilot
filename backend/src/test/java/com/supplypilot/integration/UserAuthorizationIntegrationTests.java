package com.supplypilot.integration;

import java.util.stream.Stream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;
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
import static org.assertj.core.api.Assertions.assertThat;
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
    @DisplayName("An authenticated business account without user-management capability receives 403")
    void authorizationFailureReturnsStandard403() throws Exception {
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearerToken(UserRole.PROCUREMENT_STAFF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this operation."))
                .andExpect(jsonPath("$.path").value("/api/v1/users"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("V2 ADMIN business access is separate from user management over HTTP")
    void adminBusinessAccountAndManagerHaveDistinctHttpPermissions() throws Exception {
        User business = saveUser("v2_business", UserRole.ADMIN, false);
        User manager = saveUser("v2_manager", UserRole.ADMIN, true);
        User target = saveUser("v2_target", UserRole.ADMIN, false);
        String businessToken = bearerTokenFor(business);
        String managerToken = bearerTokenFor(manager);

        mockMvc.perform(get("/api/v1/purchase-orders").header("Authorization", businessToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users").header("Authorization", businessToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/{id}", target.getId()).header("Authorization", businessToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/users").header("Authorization", businessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"v2_forbidden_create\",\"password\":\"Password123\",\"displayName\":\"Denied\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/users/{id}", target.getId()).header("Authorization", businessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Denied\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/users").header("Authorization", managerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/{id}", target.getId()).header("Authorization", managerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/users").header("Authorization", managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"v2_allowed_create\",\"password\":\"Password123\",\"displayName\":\"Allowed\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.canManageUsers").value(false));
        mockMvc.perform(patch("/api/v1/users/{id}", target.getId()).header("Authorization", managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Updated\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Existing JWT observes account deactivation and management revocation immediately")
    void existingJwtUsesCurrentDatabaseAuthorization() throws Exception {
        User actor = saveUser("v2_actor", UserRole.ADMIN, true);
        User peer = saveUser("v2_peer", UserRole.ADMIN, true);
        User inactiveAfterIssue = saveUser("v2_deactivate", UserRole.ADMIN, false);
        User firstLogin = User.create("v2_first_login_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12), "hash", "First Login",
                UserRole.ADMIN, false, clock.instant());
        firstLogin.requirePasswordChange();
        firstLogin = userRepository.saveAndFlush(firstLogin);
        String actorToken = bearerTokenFor(actor);
        String deactivatedToken = bearerTokenFor(inactiveAfterIssue);
        String peerOldToken = bearerTokenFor(peer);
        String firstLoginToken = bearerTokenFor(firstLogin);

        mockMvc.perform(get("/api/v1/purchase-orders").header("Authorization", firstLoginToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/users").header("Authorization", peerOldToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/users/{id}", peer.getId()).header("Authorization", actorToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"canManageUsers\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users").header("Authorization", peerOldToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/users/{id}", inactiveAfterIssue.getId()).header("Authorization", actorToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/purchase-orders").header("Authorization", deactivatedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        // The same database user rejects a token whose signed identity claims disagree.
        String wrongSubject = "Bearer " + jwtTokenService.generateToken(
                new AuthenticatedUser(actor.getId(), "different-subject", UserRole.ADMIN, true, false));
        mockMvc.perform(get("/api/v1/users").header("Authorization", wrongSubject))
                .andExpect(status().isUnauthorized());
        String wrongRole = "Bearer " + jwtTokenService.generateToken(
                new AuthenticatedUser(actor.getId(), actor.getUsername(), UserRole.PROCUREMENT_STAFF, true, false));
        mockMvc.perform(get("/api/v1/purchase-orders").header("Authorization", wrongRole))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Concurrent revocation of two remaining managers cannot remove both")
    void concurrentLastManagerRevocationsLeaveOneManager() throws Exception {
        List<User> priorManagers = userRepository.findAll().stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE && user.canManageUsers())
                .toList();
        try {
            priorManagers.forEach(user -> {
                user.update(null, null, UserStatus.ACTIVE, false, clock.instant());
                userRepository.saveAndFlush(user);
            });
            User first = saveUser("v2_race_first", UserRole.ADMIN, true);
            User second = saveUser("v2_race_second", UserRole.ADMIN, true);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var a = executor.submit(() -> revokeAtSameTime(first, ready, start));
                var b = executor.submit(() -> revokeAtSameTime(second, ready, start));
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                List<Integer> statuses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
                assertThat(statuses).contains(200, 403);
            }
            assertThat(userRepository.countByCanManageUsersTrueAndStatus(UserStatus.ACTIVE)).isEqualTo(1);
        } finally {
            priorManagers.forEach(user -> {
                user.update(null, null, UserStatus.ACTIVE, true, clock.instant());
                userRepository.saveAndFlush(user);
            });
        }
    }

    private int revokeAtSameTime(User target, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent request did not start");
            return mockMvc.perform(patch("/api/v1/users/{id}", target.getId())
                            .header("Authorization", bearerTokenFor(target))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"canManageUsers\":false}"))
                    .andReturn().getResponse().getStatus();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private User saveUser(String username, UserRole role, boolean canManageUsers) {
        String uniqueUsername = username + "_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        if (uniqueUsername.length() > 50) uniqueUsername = uniqueUsername.substring(0, 43) + "_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return userRepository.saveAndFlush(User.create(uniqueUsername, "hash",
                username, role, canManageUsers, clock.instant()));
    }

    private String bearerTokenFor(User user) {
        return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(),
                user.getRole(), user.canManageUsers(), user.isMustChangePassword()));
    }

    @ParameterizedTest(name = "POST users allows only ADMIN: {0}")
    @MethodSource("allRoles")
    void createUserIsAdminOnly(UserRole role) throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", bearerToken(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"role_create_%s","password":"password123","displayName":"Role Test"}
                                """.formatted(role.name().toLowerCase())))
                .andExpect(role == UserRole.ADMIN ? status().isCreated() : status().isForbidden())
                .andExpect(role == UserRole.ADMIN
                        ? jsonPath("$.role").value("ADMIN")
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
    @DisplayName("User detail requires the same user-management capability as the list")
    void getUserByIdRequiresUserManagementCapability() throws Exception {
        Long userId = createUserForUpdate();
        mockMvc.perform(get("/api/v1/users/{id}", userId)
                        .header("Authorization", bearerToken(UserRole.PRODUCTION_PLANNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
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

        AuthenticatedUser admin = new AuthenticatedUser(102L, "admin", UserRole.ADMIN, true, false);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin, null, admin.getAuthorities()
        ));
        assertThatCode(() -> userService.listUsers(org.springframework.data.domain.PageRequest.of(0, 20)))
                .doesNotThrowAnyException();
    }

    private String bearerToken(UserRole role) {
        User principal = userRepository.saveAndFlush(User.create(
                "authz_" + java.util.UUID.randomUUID().toString().replace("-", ""), "hash", role.name(),
                role == UserRole.ADMIN ? UserRole.ADMIN : role, role == UserRole.ADMIN, clock.instant()));
        return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(
                principal.getId(), principal.getUsername(), principal.getRole(), principal.canManageUsers(), false));
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
                username, "$2a$10$notARealHashForAuthorizationTests", "Target", UserRole.ADMIN,
                java.time.Instant.now()
        )).getId();
    }

    private static Stream<Arguments> allRoles() {
        return Stream.of(UserRole.values()).map(Arguments::of);
    }
}

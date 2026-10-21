package com.supplypilot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.testsupport.FixedClockConfiguration;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "supplypilot.frontend-origin=http://127.0.0.1:8080",
        "supplypilot.document-storage.path=${java.io.tmpdir}/sp903-production-bootstrap-test-documents"
})
@ActiveProfiles("prod")
@Import(FixedClockConfiguration.class)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ProductionAdminBootstrapIntegrationTests {
    private static final String JWT_SECRET = "dGVzdC1zZWNyZXQtZm9yLXNwOTAzLXByb2QtYXV0aC1hdC1sZWFzdC0yNTYtYml0cw==";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("supplypilot_prod_test")
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void productionProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", MYSQL::getJdbcUrl);
        registry.add("DB_USERNAME", MYSQL::getUsername);
        registry.add("DB_PASSWORD", MYSQL::getPassword);
        registry.add("JWT_SECRET", () -> JWT_SECRET);
        registry.add("JWT_EXPIRATION_SECONDS", () -> "3600");
        registry.add("FRONTEND_ORIGIN", () -> "http://127.0.0.1:8080");
    }

    @Autowired private ProductionAdminBootstrap bootstrap;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void resetOnlyThisDisposableProductionDatabase() {
        users.deleteAllInBatch();
        jdbc.update("DELETE FROM system_initialization");
    }

    @Test
    void cleanDatabaseBootstrapsSingleRestrictedAdminAndPasswordChangeDoesNotUpgradeOldJwt() throws Exception {
        bootstrap.run(null);
        User initial = users.findByUsername("admin").orElseThrow();
        assertThat(passwordEncoder.matches("admin", initial.getPasswordHash())).isTrue();
        assertThat(initial.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(initial.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(initial.isMustChangePassword()).isTrue();

        login("admin", "DemoDynamicOnly123", 401);
        JsonNode initialLogin = login("admin", "admin", 200);
        String restrictedJwt = initialLogin.get("accessToken").asText();
        assertThat(initialLogin.at("/user/mustChangePassword").asBoolean()).isTrue();
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(restrictedJwt)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/change-password").header("Authorization", bearer(restrictedJwt))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"admin\",\"newPassword\":\"NewSecurePassword123\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(restrictedJwt)))
                .andExpect(status().isForbidden());
        login("admin", "admin", 401);
        login("admin", "NewSecurePassword123", 200);

        bootstrap.run(null);
        login("admin", "admin", 401);
        JsonNode stillChanged = login("admin", "NewSecurePassword123", 200);
        mockMvc.perform(get("/api/v1/users").header("Authorization",
                        bearer(stillChanged.get("accessToken").asText())))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_initialization", Long.class)).isEqualTo(1);
    }

    @Test
    void persistedMarkerPreventsRecreationWhenUsersHaveBeenRemoved() {
        jdbc.update("INSERT INTO system_initialization (initialization_key, initialized_at) VALUES (?, CURRENT_TIMESTAMP(6))",
                "production-admin-bootstrap-v1");

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active user manager");
        assertThat(users.count()).isZero();
    }

    @Test
    void activeBusinessAdminWithoutManagementCapabilityFailsClosed() {
        User businessAdmin = User.create("ordinary_admin", passwordEncoder.encode("StaffPass123"), "Business Admin",
                UserRole.ADMIN, false, Instant.now());
        users.saveAndFlush(businessAdmin);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active user manager");
        assertThat(users.findByUsername("admin")).isEmpty();
        assertThat(users.findByUsername("ordinary_admin")).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_initialization", Long.class)).isEqualTo(1);
    }

    @Test
    void bootstrapMarkerAndAdminInsertRollBackTogetherOnFailure() {
        jdbc.execute("""
                CREATE TRIGGER fail_admin_insert BEFORE INSERT ON users FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'isolated bootstrap test failure'
                """);
        try {
            assertThatThrownBy(() -> bootstrap.run(null)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_initialization", Long.class)).isZero();
            assertThat(users.count()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_admin_insert");
        }
    }

    @Test
    void concurrentBootstrapCallsCreateOnlyOneAdminAndOneMarker() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> tasks = List.of(
                    executor.submit(() -> bootstrap.run(null)),
                    executor.submit(() -> bootstrap.run(null)));
            for (Future<?> task : tasks) task.get();
        }
        assertThat(users.findAll()).hasSize(1);
        assertThat(users.findByUsername("admin")).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_initialization", Long.class)).isEqualTo(1);
    }

    private JsonNode login(String username, String password, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", username, "password", password))))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return expectedStatus == 200 ? objectMapper.readTree(response) : objectMapper.nullNode();
    }

    private String bearer(String token) { return "Bearer " + token; }
}

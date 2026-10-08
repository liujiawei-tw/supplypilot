package com.supplypilot.integration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("Complete User Domain and Management flow: create, read, list, update, reject duplicate")
    void testCompleteUserManagementFlow() throws Exception {
        // 1. Create user
        String createResponseJson = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "e2e_planner",
                                  "password": "secretPass_2026",
                                  "displayName": "Production Planner E2E",
                                  "role": "PRODUCTION_PLANNER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("e2e_planner"))
                .andExpect(jsonPath("$.displayName").value("Production Planner E2E"))
                .andExpect(jsonPath("$.role").value("PRODUCTION_PLANNER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Matcher matcher = Pattern.compile("\"id\":\\s*(\\d+)").matcher(createResponseJson);
        assertThat(matcher.find()).isTrue();
        Long userId = Long.parseLong(matcher.group(1));

        // Verify in database: password is BCrypt hashed, raw password is not stored
        User persistedUser = userRepository.findById(userId).orElseThrow();
        assertThat(persistedUser.getPasswordHash()).isNotEqualTo("secretPass_2026");
        assertThat(passwordEncoder.matches("secretPass_2026", persistedUser.getPasswordHash())).isTrue();

        // 2. Query user by ID
        mockMvc.perform(get("/api/v1/users/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.username").value("e2e_planner"))
                .andExpect(jsonPath("$.displayName").value("Production Planner E2E"))
                .andExpect(jsonPath("$.role").value("PRODUCTION_PLANNER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        // 3. Query non-existent user returns 404 USER_NOT_FOUND
        mockMvc.perform(get("/api/v1/users/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        // 4. Duplicate username creation should return 409 Conflict with DUPLICATE_USERNAME
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "e2e_planner",
                                  "password": "anotherPassword",
                                  "displayName": "Another Planner",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_USERNAME"));

        // 5. Update user
        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Updated Planner Name",
                                  "role": "PROCUREMENT_MANAGER",
                                  "status": "INACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.displayName").value("Updated Planner Name"))
                .andExpect(jsonPath("$.role").value("PROCUREMENT_MANAGER"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        // Verify in DB that status is INACTIVE
        User updatedInDb = userRepository.findById(userId).orElseThrow();
        assertThat(updatedInDb.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(updatedInDb.getDisplayName()).isEqualTo("Updated Planner Name");
        assertThat(updatedInDb.getRole()).isEqualTo(UserRole.PROCUREMENT_MANAGER);

        // 6. List users with pagination
        mockMvc.perform(get("/api/v1/users")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());

        // 7. Test invalid pagination size validation (size = 0, size = 101, page = -1)
        mockMvc.perform(get("/api/v1/users")
                        .param("page", "0")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/users")
                        .param("page", "0")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/users")
                        .param("page", "-1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Username with surrounding whitespace is rejected as duplicate against normalized username")
    void testUsernameWhitespaceDuplication() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "space_user",
                                  "password": "password123",
                                  "displayName": "Space User",
                                  "role": "WAREHOUSE_STAFF"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "   space_user   ",
                                  "password": "password123",
                                  "displayName": "Duplicate Space User",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_USERNAME"));
    }

    @Test
    @DisplayName("Username with different casing is rejected under MySQL utf8mb4_unicode_ci collation")
    void testUsernameCaseInsensitiveCollation() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "case_user",
                                  "password": "password123",
                                  "displayName": "Case User",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "CASE_USER",
                                  "password": "password123",
                                  "displayName": "Uppercase Case User",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_USERNAME"));
    }

    @ParameterizedTest(name = "PATCH /api/v1/users/[id] rejects disallowed field: {0}")
    @ValueSource(strings = {"password", "passwordHash", "username", "id", "createdAt", "updatedAt"})
    void testDisallowedFieldsInPatch(String disallowedField) throws Exception {
        // Create user first
        String response = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "patch_disallowed_%s",
                                  "password": "password123",
                                  "displayName": "Test User",
                                  "role": "ADMIN"
                                }
                                """.formatted(disallowedField)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Matcher matcher = Pattern.compile("\"id\":\\s*(\\d+)").matcher(response);
        assertThat(matcher.find()).isTrue();
        Long id = Long.parseLong(matcher.group(1));

        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "%s": "forbiddenValue"
                                }
                                """.formatted(disallowedField)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/{id} rejects blank displayName")
    void testBlankDisplayNameInPatch() throws Exception {
        String response = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "patch_blank_name_user",
                                  "password": "password123",
                                  "displayName": "Original Name",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Matcher matcher = Pattern.compile("\"id\":\\s*(\\d+)").matcher(response);
        assertThat(matcher.find()).isTrue();
        Long id = Long.parseLong(matcher.group(1));

        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Invalid UserRole or UserStatus returns 400 VALIDATION_FAILED, not 500")
    void testInvalidEnums() throws Exception {
        // Invalid UserRole on create
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "invalid_enum_user",
                                  "password": "password123",
                                  "displayName": "User",
                                  "role": "SUPER_USER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Create valid user
        String response = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "valid_user_for_status",
                                  "password": "password123",
                                  "displayName": "User",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Matcher matcher = Pattern.compile("\"id\":\\s*(\\d+)").matcher(response);
        assertThat(matcher.find()).isTrue();
        Long id = Long.parseLong(matcher.group(1));

        // Invalid UserStatus on patch
        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "DELETED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Length boundary validation for username and displayName")
    void testLengthBoundaries() throws Exception {
        String username51 = "u".repeat(51);
        String name101 = "d".repeat(101);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "password123",
                                  "displayName": "Valid Name",
                                  "role": "ADMIN"
                                }
                                """.formatted(username51)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "valid_user_name",
                                  "password": "password123",
                                  "displayName": "%s",
                                  "role": "ADMIN"
                                }
                                """.formatted(name101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}

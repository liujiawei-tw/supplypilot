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
}

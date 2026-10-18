package com.supplypilot.user.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.exception.GlobalExceptionHandler;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.dto.request.CreateUserRequest;
import com.supplypilot.user.dto.request.UpdateUserRequest;
import com.supplypilot.user.dto.response.UserResponse;
import com.supplypilot.user.service.UserService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTests {

    @Mock
    private UserService userService;

    private MockMvc mockMvc;

    private static final Instant NOW = TestDataConstants.TEST_INSTANT;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
                .setControllerAdvice(new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC)))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/users - 201 Created on valid request")
    void shouldCreateUserSuccessfully() throws Exception {
        UserResponse response = new UserResponse(
                1L,
                "john_buyer",
                "John Buyer",
                UserRole.PROCUREMENT_STAFF,
                UserStatus.ACTIVE,
                NOW,
                NOW
        );

        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "john_buyer",
                                  "password": "securePassword123",
                                  "displayName": "John Buyer",
                                  "role": "PROCUREMENT_STAFF"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("john_buyer"))
                .andExpect(jsonPath("$.displayName").value("John Buyer"))
                .andExpect(jsonPath("$.role").value("PROCUREMENT_STAFF"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/users - password length boundary: rejects 5, accepts 6 and 12")
    void shouldEnforceSixCharacterMinimumPassword() throws Exception {
        UserResponse response = new UserResponse(
                2L, "boundary_user", "Boundary User", UserRole.ADMIN, UserStatus.ACTIVE, NOW, NOW);
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("abcde")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("abcdef")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("abcdefghijkl")))
                .andExpect(status().isCreated());
    }

    private String createUserJson(String password) {
        return """
                {
                  "username": "boundary_user",
                  "password": "%s",
                  "displayName": "Boundary User",
                  "role": "ADMIN"
                }
                """.formatted(password);
    }

    @Test
    @DisplayName("POST /api/v1/users - 400 Bad Request on blank username or password")
    void shouldReturnBadRequestWhenValidationFails() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "",
                                  "password": "",
                                  "displayName": "",
                                  "role": null
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("POST /api/v1/users - 409 Conflict on duplicate username")
    void shouldReturnConflictOnDuplicateUsername() throws Exception {
        when(userService.createUser(any(CreateUserRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_USERNAME));

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "duplicate_user",
                                  "password": "password123",
                                  "displayName": "Duplicate User",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_USERNAME"));
    }

    @Test
    @DisplayName("POST /api/v1/users - 400 Bad Request on invalid UserRole")
    void shouldReturnBadRequestOnInvalidRole() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "valid_user",
                                  "password": "password123",
                                  "displayName": "Valid User",
                                  "role": "SUPER_ADMIN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/users - 400 Bad Request on passing unknown/backend-owned fields")
    void shouldRejectUnknownFieldsInCreateUser() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "valid_user",
                                  "password": "password123",
                                  "displayName": "Valid User",
                                  "role": "ADMIN",
                                  "id": 100,
                                  "createdAt": "2026-10-08T00:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/users - boundary: username length 50 succeeds, 51 fails")
    void shouldValidateUsernameLengthBoundary() throws Exception {
        String username50 = "a".repeat(50);
        String username51 = "a".repeat(51);

        UserResponse response = new UserResponse(1L, username50, "Name", UserRole.ADMIN, UserStatus.ACTIVE, NOW, NOW);
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(response);

        // 50 chars succeeds
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "password123",
                                  "displayName": "Name",
                                  "role": "ADMIN"
                                }
                                """.formatted(username50)))
                .andExpect(status().isCreated());

        // 51 chars fails
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "password123",
                                  "displayName": "Name",
                                  "role": "ADMIN"
                                }
                                """.formatted(username51)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/users - boundary: displayName length 100 succeeds, 101 fails")
    void shouldValidateDisplayNameLengthBoundaryInCreate() throws Exception {
        String name100 = "b".repeat(100);
        String name101 = "b".repeat(101);

        UserResponse response = new UserResponse(1L, "user_boundary", name100, UserRole.ADMIN, UserStatus.ACTIVE, NOW, NOW);
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(response);

        // 100 chars succeeds
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "user_boundary",
                                  "password": "password123",
                                  "displayName": "%s",
                                  "role": "ADMIN"
                                }
                                """.formatted(name100)))
                .andExpect(status().isCreated());

        // 101 chars fails
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "user_boundary",
                                  "password": "password123",
                                  "displayName": "%s",
                                  "role": "ADMIN"
                                }
                                """.formatted(name101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /api/v1/users - 200 OK with paginated list")
    void shouldReturnPaginatedUsers() throws Exception {
        UserResponse u1 = new UserResponse(1L, "u1", "User 1", UserRole.ADMIN, UserStatus.ACTIVE, NOW, NOW);
        UserResponse u2 = new UserResponse(2L, "u2", "User 2", UserRole.WAREHOUSE_STAFF, UserStatus.ACTIVE, NOW, NOW);
        PageResponse<UserResponse> pageResponse = new PageResponse<>(List.of(u1, u2), 0, 20, 2, 1);

        when(userService.listUsers(any(PageRequest.class))).thenReturn(pageResponse);

        mockMvc.perform(get("/api/v1/users")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/users/{id} - 200 OK when user exists")
    void shouldGetUserById() throws Exception {
        UserResponse response = new UserResponse(
                5L,
                "admin_user",
                "System Admin",
                UserRole.ADMIN,
                UserStatus.ACTIVE,
                NOW,
                NOW
        );

        when(userService.getUserById(5L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/users/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.username").value("admin_user"))
                .andExpect(jsonPath("$.displayName").value("System Admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/users/{id} - 404 Not Found when user does not exist")
    void shouldReturnNotFoundWhenUserDoesNotExist() throws Exception {
        when(userService.getUserById(999L))
                .thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/users/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/{id} - 200 OK on successful profile update")
    void shouldUpdateUserSuccessfully() throws Exception {
        UserResponse response = new UserResponse(
                3L,
                "user_to_update",
                "Updated Name",
                UserRole.PROCUREMENT_MANAGER,
                UserStatus.INACTIVE,
                NOW,
                NOW.plusSeconds(60)
        );

        when(userService.updateUser(eq(3L), any(UpdateUserRequest.class))).thenReturn(response);

        mockMvc.perform(patch("/api/v1/users/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Updated Name",
                                  "role": "PROCUREMENT_MANAGER",
                                  "status": "INACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated Name"))
                .andExpect(jsonPath("$.role").value("PROCUREMENT_MANAGER"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("PATCH /api/v1/users/{id} - 404 Not Found on updating non-existent user")
    void shouldReturnNotFoundWhenUpdatingNonExistentUser() throws Exception {
        when(userService.updateUser(eq(999L), any(UpdateUserRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));

        mockMvc.perform(patch("/api/v1/users/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Name"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @ParameterizedTest(name = "PATCH /api/v1/users/[id] - 400 Bad Request on unknown field: {0}")
    @ValueSource(strings = {"password", "passwordHash", "username", "id", "createdAt", "updatedAt"})
    void shouldRejectDisallowedFieldsInPatch(String disallowedField) throws Exception {
        mockMvc.perform(patch("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "%s": "someValue"
                                }
                                """.formatted(disallowedField)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/{id} - 400 Bad Request on blank displayName")
    void shouldRejectBlankDisplayNameInPatch() throws Exception {
        mockMvc.perform(patch("/api/v1/users/1")
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
    @DisplayName("PATCH /api/v1/users/{id} - 400 Bad Request on invalid UserRole")
    void shouldRejectInvalidRoleInPatch() throws Exception {
        mockMvc.perform(patch("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "UNKNOWN_ROLE"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/{id} - 400 Bad Request on invalid UserStatus")
    void shouldRejectInvalidStatusInPatch() throws Exception {
        mockMvc.perform(patch("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "UNKNOWN_STATUS"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/{id} - boundary: displayName length 100 succeeds, 101 fails")
    void shouldValidateDisplayNameLengthBoundaryInPatch() throws Exception {
        String name100 = "c".repeat(100);
        String name101 = "c".repeat(101);

        UserResponse response = new UserResponse(1L, "user_patch", name100, UserRole.ADMIN, UserStatus.ACTIVE, NOW, NOW);
        when(userService.updateUser(eq(1L), any(UpdateUserRequest.class))).thenReturn(response);

        // 100 chars succeeds
        mockMvc.perform(patch("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "%s"
                                }
                                """.formatted(name100)))
                .andExpect(status().isOk());

        // 101 chars fails
        mockMvc.perform(patch("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "%s"
                                }
                                """.formatted(name101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}

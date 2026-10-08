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
}

package com.supplypilot.user.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.dto.request.CreateUserRequest;
import com.supplypilot.user.dto.request.UpdateUserRequest;
import com.supplypilot.user.dto.response.UserResponse;
import com.supplypilot.user.mapper.UserMapper;
import com.supplypilot.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTests {

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private Clock fixedClock;
    private UserMapper userMapper;
    private UserService userService;

    private static final Instant FIXED_NOW = TestDataConstants.TEST_INSTANT;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        userMapper = new UserMapper();
        userService = new UserService(userRepository, passwordEncoder, fixedClock, userMapper);
    }

    @Test
    @DisplayName("Should create user with BCrypt hashed password, normalized username, and valid fields")
    void shouldCreateUserWithHashedPassword() {
        CreateUserRequest request = new CreateUserRequest(
                "  buyer_service_test  ",
                "secretPassword123",
                "Buyer User",
                UserRole.PROCUREMENT_STAFF
        );

        when(userRepository.existsByUsername("buyer_service_test")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.createUser(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User savedUser = captor.getValue();

        assertThat(savedUser.getUsername()).isEqualTo("buyer_service_test");
        assertThat(savedUser.getPasswordHash()).isNotEqualTo("secretPassword123");
        assertThat(passwordEncoder.matches("secretPassword123", savedUser.getPasswordHash())).isTrue();
        assertThat(savedUser.getDisplayName()).isEqualTo("Buyer User");
        assertThat(savedUser.getRole()).isEqualTo(UserRole.PROCUREMENT_STAFF);
        assertThat(savedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(savedUser.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(savedUser.getUpdatedAt()).isEqualTo(FIXED_NOW);

        // Security check: response DTO contains no password/hash
        assertThat(response.username()).isEqualTo("buyer_service_test");
        assertThat(response.displayName()).isEqualTo("Buyer User");
        assertThat(response.role()).isEqualTo(UserRole.PROCUREMENT_STAFF);
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("Should throw DUPLICATE_USERNAME when creating user with existing username (including whitespace)")
    void shouldThrowDuplicateUsername() {
        CreateUserRequest request = new CreateUserRequest(
                "  existing_user  ",
                "secretPassword123",
                "Existing User",
                UserRole.ADMIN
        );

        when(userRepository.existsByUsername("existing_user")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_USERNAME);

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("Should translate DataIntegrityViolationException to DUPLICATE_USERNAME on concurrent race insert")
    void shouldTranslateDataIntegrityViolationExceptionOnRaceCondition() {
        CreateUserRequest request = new CreateUserRequest(
                "concurrent_user",
                "secretPassword123",
                "Concurrent User",
                UserRole.ADMIN
        );

        when(userRepository.existsByUsername("concurrent_user")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry 'concurrent_user' for key 'uk_users_username'"));

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_USERNAME);
    }

    @Test
    @DisplayName("Should retrieve user by id successfully")
    void shouldGetUserById() {
        User user = User.create("manager_service_test", "$2a$10$hash", "Manager User", UserRole.PROCUREMENT_MANAGER, FIXED_NOW);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserResponse response = userService.getUserById(1L);

        assertThat(response.username()).isEqualTo("manager_service_test");
        assertThat(response.displayName()).isEqualTo("Manager User");
        assertThat(response.role()).isEqualTo(UserRole.PROCUREMENT_MANAGER);
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("Should throw USER_NOT_FOUND when getting non-existent user")
    void shouldThrowUserNotFoundOnGet() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("Should list users with pagination")
    void shouldListUsers() {
        User user1 = User.create("user1", "$2a$10$hash1", "User One", UserRole.ADMIN, FIXED_NOW);
        User user2 = User.create("user2", "$2a$10$hash2", "User Two", UserRole.WAREHOUSE_STAFF, FIXED_NOW);
        Page<User> page = new PageImpl<>(List.of(user1, user2), PageRequest.of(0, 20), 2);

        when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);

        PageResponse<UserResponse> response = userService.listUsers(PageRequest.of(0, 20));

        assertThat(response.content()).hasSize(2);
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should update user profile, role, and status")
    void shouldUpdateUser() {
        User existingUser = User.create("warehouse_user", "$2a$10$hash", "Old Name", UserRole.WAREHOUSE_STAFF, FIXED_NOW);
        when(userRepository.findById(2L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateUserRequest updateRequest = new UpdateUserRequest(
                "New Name",
                UserRole.ADMIN,
                UserStatus.INACTIVE
        );

        UserResponse response = userService.updateUser(2L, updateRequest);

        assertThat(response.displayName()).isEqualTo("New Name");
        assertThat(response.role()).isEqualTo(UserRole.ADMIN);
        assertThat(response.status()).isEqualTo(UserStatus.INACTIVE);
    }

    @Test
    @DisplayName("Should throw VALIDATION_FAILED when updating user with blank display name")
    void shouldThrowValidationFailedOnBlankDisplayName() {
        UpdateUserRequest updateRequest = new UpdateUserRequest("   ", null, null);

        assertThatThrownBy(() -> userService.updateUser(1L, updateRequest))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Should throw USER_NOT_FOUND when updating non-existent user")
    void shouldThrowUserNotFoundOnUpdate() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        UpdateUserRequest updateRequest = new UpdateUserRequest("New Name", null, null);

        assertThatThrownBy(() -> userService.updateUser(999L, updateRequest))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}

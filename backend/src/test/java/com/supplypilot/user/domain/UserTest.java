package com.supplypilot.user.domain;

import java.time.Instant;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.testsupport.TestDataConstants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    private static final Instant NOW = TestDataConstants.TEST_INSTANT;
    private static final Instant LATER = NOW.plusSeconds(3600);

    @Test
    @DisplayName("Should successfully create a user with default ACTIVE status and matching timestamps")
    void shouldCreateUserWithActiveStatus() {
        User user = User.create("john_doe", "$2a$10$hashedpassword", "John Doe", UserRole.PROCUREMENT_STAFF, NOW);

        assertThat(user.getUsername()).isEqualTo("john_doe");
        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$hashedpassword");
        assertThat(user.getDisplayName()).isEqualTo("John Doe");
        assertThat(user.getRole()).isEqualTo(UserRole.PROCUREMENT_STAFF);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Should throw exception when creating user with blank required fields")
    void shouldThrowWhenCreatingWithBlankFields() {
        assertThatThrownBy(() -> User.create(" ", "$2a$10$hashedpassword", "John Doe", UserRole.ADMIN, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> User.create("john_doe", " ", "John Doe", UserRole.ADMIN, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> User.create("john_doe", "$2a$10$hashedpassword", " ", UserRole.ADMIN, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Should update user profile fields and update timestamp")
    void shouldUpdateUserProfileFields() {
        User user = User.create("john_doe", "$2a$10$hashedpassword", "John Doe", UserRole.PROCUREMENT_STAFF, NOW);

        user.update("John Smith", UserRole.PROCUREMENT_MANAGER, UserStatus.INACTIVE, LATER);

        assertThat(user.getDisplayName()).isEqualTo("John Smith");
        assertThat(user.getRole()).isEqualTo(UserRole.PROCUREMENT_MANAGER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(user.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("Should throw exception when updating with empty display name")
    void shouldThrowWhenUpdatingWithEmptyDisplayName() {
        User user = User.create("john_doe", "$2a$10$hashedpassword", "John Doe", UserRole.PROCUREMENT_STAFF, NOW);

        assertThatThrownBy(() -> user.update("   ", null, null, LATER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Should activate and inactivate user")
    void shouldActivateAndInactivateUser() {
        User user = User.create("john_doe", "$2a$10$hashedpassword", "John Doe", UserRole.PROCUREMENT_STAFF, NOW);

        user.inactivate(LATER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(user.getUpdatedAt()).isEqualTo(LATER);

        Instant muchLater = LATER.plusSeconds(3600);
        user.activate(muchLater);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getUpdatedAt()).isEqualTo(muchLater);
    }

    @Test
    @DisplayName("User toString should not contain passwordHash")
    void toStringShouldNotContainPasswordHash() {
        User user = User.create("john_doe", "$2a$10$secretHash123", "John Doe", UserRole.ADMIN, NOW);

        assertThat(user.toString()).doesNotContain("$2a$10$secretHash123");
        assertThat(user.toString()).doesNotContain("passwordHash");
    }
}

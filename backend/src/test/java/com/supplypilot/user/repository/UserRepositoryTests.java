package com.supplypilot.user.repository;

import java.time.Instant;
import java.util.Optional;

import com.supplypilot.integration.AbstractIntegrationTest;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryTests extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    private static final Instant NOW = TestDataConstants.TEST_INSTANT;

    @Test
    @DisplayName("Should persist and find user by username")
    void shouldPersistAndFindByUsername() {
        User user = User.create("planner_repo_test", "$2a$10$hashvalue", "Production Planner", UserRole.PRODUCTION_PLANNER, NOW);
        userRepository.saveAndFlush(user);

        Optional<User> found = userRepository.findByUsername("planner_repo_test");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isNotNull();
        assertThat(found.get().getUsername()).isEqualTo("planner_repo_test");
        assertThat(found.get().getDisplayName()).isEqualTo("Production Planner");
        assertThat(found.get().getRole()).isEqualTo(UserRole.PRODUCTION_PLANNER);
        assertThat(found.get().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("Should return true when user exists by username")
    void shouldCheckExistsByUsername() {
        User user = User.create("buyer_repo_test", "$2a$10$hashvalue", "Procurement Buyer", UserRole.PROCUREMENT_STAFF, NOW);
        userRepository.saveAndFlush(user);

        assertThat(userRepository.existsByUsername("buyer_repo_test")).isTrue();
        assertThat(userRepository.existsByUsername("non_existent_user")).isFalse();
    }

    @Test
    @DisplayName("Should enforce database UNIQUE(username) constraint and reject duplicate username")
    void shouldRejectDuplicateUsernameAtDatabaseLevel() {
        User user1 = User.create("duplicate_user_test", "$2a$10$hashvalue1", "User One", UserRole.ADMIN, NOW);
        userRepository.saveAndFlush(user1);

        User user2 = User.create("duplicate_user_test", "$2a$10$hashvalue2", "User Two", UserRole.WAREHOUSE_STAFF, NOW);

        assertThatThrownBy(() -> userRepository.saveAndFlush(user2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

package com.supplypilot.user.repository;

import java.util.Optional;
import java.util.List;

import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByRoleAndStatus(UserRole role, UserStatus status);

    boolean existsByCanManageUsersTrueAndStatus(UserStatus status);
    List<User> findByRoleAndStatusOrderByDisplayNameAsc(UserRole role, UserStatus status);

    long countByCanManageUsersTrueAndStatus(UserStatus status);

    /**
     * Locks every currently active user manager in stable id order. Mutations that
     * can remove or grant management capability use this current-read query before
     * making their decision, serializing the last-manager invariant in MySQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.canManageUsers = true and u.status = :status order by u.id")
    List<User> findActiveManagersForUpdate(@Param("status") UserStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}

package com.supplypilot.user.domain;

import java.time.Instant;
import java.util.Objects;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public static User create(
            String username,
            String passwordHash,
            String displayName,
            UserRole role,
            Instant now
    ) {
        Objects.requireNonNull(username, "Username must not be null");
        Objects.requireNonNull(passwordHash, "Password hash must not be null");
        Objects.requireNonNull(displayName, "Display name must not be null");
        Objects.requireNonNull(role, "Role must not be null");
        Objects.requireNonNull(now, "Timestamp must not be null");

        String trimmedUsername = username.trim();
        String trimmedDisplayName = displayName.trim();

        if (trimmedUsername.isEmpty() || trimmedDisplayName.isEmpty() || passwordHash.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        User user = new User();
        user.username = trimmedUsername;
        user.passwordHash = passwordHash;
        user.displayName = trimmedDisplayName;
        user.role = role;
        user.status = UserStatus.ACTIVE;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public void update(
            String newDisplayName,
            UserRole newRole,
            UserStatus newStatus,
            Instant now
    ) {
        Objects.requireNonNull(now, "Timestamp must not be null");
        boolean modified = false;

        if (newDisplayName != null) {
            String trimmed = newDisplayName.trim();
            if (trimmed.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (!trimmed.equals(this.displayName)) {
                this.displayName = trimmed;
                modified = true;
            }
        }

        if (newRole != null && newRole != this.role) {
            this.role = newRole;
            modified = true;
        }

        if (newStatus != null && newStatus != this.status) {
            this.status = newStatus;
            modified = true;
        }

        if (modified) {
            this.updatedAt = now;
        }
    }

    public void activate(Instant now) {
        Objects.requireNonNull(now, "Timestamp must not be null");
        if (this.status != UserStatus.ACTIVE) {
            this.status = UserStatus.ACTIVE;
            this.updatedAt = now;
        }
    }

    public void inactivate(Instant now) {
        Objects.requireNonNull(now, "Timestamp must not be null");
        if (this.status != UserStatus.INACTIVE) {
            this.status = UserStatus.INACTIVE;
            this.updatedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", displayName='" + displayName + '\'' +
                ", role=" + role +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

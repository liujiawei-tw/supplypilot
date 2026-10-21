package com.supplypilot.user.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.supplypilot.user.domain.UserRole;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateUserRequest {

    @NotBlank(message = "Username is required.")
    @Size(max = 50, message = "Username must not exceed 50 characters.")
    private String username;

    @NotBlank(message = "Password is required.")
    @Size(min = 6, max = 100, message = "Password must be between 6 and 100 characters.")
    private String password;

    @NotBlank(message = "Display name is required.")
    @Size(max = 100, message = "Display name must not exceed 100 characters.")
    private String displayName;

    private UserRole role;
    private boolean canManageUsers;

    /**
     * No-arg constructor for Jackson setter-based deserialization.
     * Enables @JsonAnySetter to intercept unknown properties before construction.
     */
    public CreateUserRequest() {
    }

    /**
     * Full constructor for use in tests and internal code.
     */
    public CreateUserRequest(String username, String password, String displayName, UserRole role) {
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.role = role;
    }

    @JsonSetter("username")
    public void setUsername(String username) {
        this.username = username;
    }

    @JsonSetter("password")
    public void setPassword(String password) {
        this.password = password;
    }

    @JsonSetter("displayName")
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @JsonSetter("role")
    public void setRole(UserRole role) {
        this.role = role;
    }

    @JsonSetter("canManageUsers")
    public void setCanManageUsers(boolean canManageUsers) { this.canManageUsers = canManageUsers; }

    /**
     * Rejects any JSON property not explicitly declared above.
     * Throwing here propagates as HttpMessageNotReadableException → 400 VALIDATION_FAILED.
     */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String displayName() {
        return displayName;
    }

    public UserRole role() {
        return role;
    }

    public boolean canManageUsers() { return canManageUsers; }
}

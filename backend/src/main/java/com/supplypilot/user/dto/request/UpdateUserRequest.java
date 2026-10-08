package com.supplypilot.user.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UpdateUserRequest {

    @Pattern(regexp = ".*\\S.*", message = "Display name must not be blank.")
    @Size(max = 100, message = "Display name must not exceed 100 characters.")
    private String displayName;

    private UserRole role;

    private UserStatus status;

    /**
     * No-arg constructor for Jackson setter-based deserialization.
     * Enables @JsonAnySetter to intercept unknown properties before construction.
     */
    public UpdateUserRequest() {
    }

    /**
     * Full constructor for use in tests and internal code.
     */
    public UpdateUserRequest(String displayName, UserRole role, UserStatus status) {
        this.displayName = displayName;
        this.role = role;
        this.status = status;
    }

    @JsonSetter("displayName")
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @JsonSetter("role")
    public void setRole(UserRole role) {
        this.role = role;
    }

    @JsonSetter("status")
    public void setStatus(UserStatus status) {
        this.status = status;
    }

    /**
     * Rejects any JSON property not explicitly declared above.
     * Throwing here propagates as HttpMessageNotReadableException → 400 VALIDATION_FAILED.
     */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }

    public String displayName() {
        return displayName;
    }

    public UserRole role() {
        return role;
    }

    public UserStatus status() {
        return status;
    }
}

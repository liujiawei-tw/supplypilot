package com.supplypilot.security;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ChangePasswordRequest {
    @NotBlank
    private String currentPassword;

    @NotBlank
    @Size(min = 6, max = 100, message = "Password must be between 6 and 100 characters.")
    private String newPassword;

    @JsonSetter("currentPassword")
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }

    @JsonSetter("newPassword")
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }

    public String currentPassword() { return currentPassword; }
    public String newPassword() { return newPassword; }
}

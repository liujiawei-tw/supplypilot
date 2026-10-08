package com.supplypilot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("supplypilot")
public record ProductionConfigurationProperties(
        @Valid Jwt jwt,
        @NotBlank String frontendOrigin,
        @Valid DocumentStorage documentStorage
) {

    public record Jwt(
            @NotBlank String secret,
            @Positive long expirationSeconds
    ) {
    }

    public record DocumentStorage(
            @NotBlank String type,
            @NotBlank String path
    ) {
    }
}


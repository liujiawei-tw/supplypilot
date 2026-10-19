package com.supplypilot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
            @NotBlank @Pattern(regexp = "local|s3", message = "Document storage type must be local or s3") String type,
            @NotBlank String path
    ) {
    }
}


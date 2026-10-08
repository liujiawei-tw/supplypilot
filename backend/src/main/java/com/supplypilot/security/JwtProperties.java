package com.supplypilot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Typed configuration for JWT settings bound from {@code supplypilot.jwt.*}.
 *
 * <p>Secret must be a Base64-encoded high-entropy string of at least 256 bits.
 * Production must supply {@code JWT_SECRET} environment variable; an empty
 * default is intentional so that a blank value causes a startup failure rather
 * than silently falling back to a weak default.
 */
@Validated
@ConfigurationProperties(prefix = "supplypilot.jwt")
public record JwtProperties(

        @NotBlank(message = "JWT secret must not be blank. Set the JWT_SECRET environment variable.")
        String secret,

        @Positive(message = "JWT expiration-seconds must be a positive integer.")
        long expirationSeconds

) {
}

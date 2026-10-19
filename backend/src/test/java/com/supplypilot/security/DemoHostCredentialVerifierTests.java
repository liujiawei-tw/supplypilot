package com.supplypilot.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class DemoHostCredentialVerifierTests {
    @Test
    void acceptsOnlyHttpsVerifierEndpointsWithAllowedIdentityAndPath() {
        assertDoesNotThrow(() -> DemoHostCredentialVerifier.validateVerifierUri(
                URI.create("https://host.docker.internal:18443/v1/verify")));
        assertDoesNotThrow(() -> DemoHostCredentialVerifier.validateVerifierUri(
                URI.create("https://localhost:38443/v1/verify")));

        for (String value : new String[] {
                "http://host.docker.internal:18443/v1/verify",
                "https://user:pass@host.docker.internal:18443/v1/verify",
                "https://other.example:18443/v1/verify",
                "https://host.docker.internal:18444/v1/verify",
                "https://host.docker.internal:18443/other",
                "https://host.docker.internal:18443/v1/verify?redirect=http://example.com",
                "https://host.docker.internal:18443/v1/verify#fragment"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> DemoHostCredentialVerifier.validateVerifierUri(URI.create(value)), value);
        }
    }
}

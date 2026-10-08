package com.supplypilot.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityCorsTests {

    @Test
    @DisplayName("Local environment without frontend-origin falls back safely to localhost dev servers")
    void testLocalEnvironmentFallback() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");

        SecurityConfig config = new SecurityConfig(
                null,
                null,
                null,
                null,
                null,
                "",
                env
        );

        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
        CorsConfiguration corsConfig = source.getCorsConfigurations().get("/**");

        assertThat(corsConfig).isNotNull();
        assertThat(corsConfig.getAllowedOrigins()).containsExactlyInAnyOrder("http://localhost:5173", "http://localhost:3000");
        assertThat(corsConfig.getAllowCredentials()).isTrue();
    }

    @Test
    @DisplayName("Environment with explicit frontend-origin sets exact allowed origins")
    void testExplicitFrontendOrigin() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        SecurityConfig config = new SecurityConfig(
                null,
                null,
                null,
                null,
                null,
                "https://app.supplypilot.com, https://admin.supplypilot.com",
                env
        );

        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
        CorsConfiguration corsConfig = source.getCorsConfigurations().get("/**");

        assertThat(corsConfig).isNotNull();
        assertThat(corsConfig.getAllowedOrigins()).containsExactly("https://app.supplypilot.com", "https://admin.supplypilot.com");
    }

    @Test
    @DisplayName("Production environment missing frontend-origin fails fast with IllegalStateException")
    void testProductionMissingFrontendOriginFailsFast() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        SecurityConfig config = new SecurityConfig(
                null,
                null,
                null,
                null,
                null,
                "",
                env
        );

        assertThatThrownBy(config::corsConfigurationSource)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be configured in production");
    }

    @Test
    @DisplayName("Production environment with wildcard '*' origin fails fast with IllegalStateException")
    void testProductionWildcardOriginFailsFast() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        SecurityConfig config = new SecurityConfig(
                null,
                null,
                null,
                null,
                null,
                "*",
                env
        );

        assertThatThrownBy(config::corsConfigurationSource)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact non-wildcard origin");
    }

    @Test
    @DisplayName("Production environment with only blank origin entries fails fast")
    void testProductionBlankOriginEntriesFailFast() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        SecurityConfig config = new SecurityConfig(
                null,
                null,
                null,
                null,
                null,
                " , ",
                env
        );

        assertThatThrownBy(config::corsConfigurationSource)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact non-wildcard origin");
    }
}

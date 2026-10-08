package com.supplypilot.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.exception.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class CustomAuthenticationEntryPointTests {

    private CustomAuthenticationEntryPoint entryPoint;
    private ObjectMapper objectMapper;
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        entryPoint = new CustomAuthenticationEntryPoint(objectMapper, clock);
    }

    @Test
    @DisplayName("Commence writes 401 status and JSON ErrorResponse with UNAUTHENTICATED code")
    void testCommenceOutputsStandardErrorResponse() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("Bad credentials"));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentType()).contains("application/json");

        String content = response.getContentAsString();
        ErrorResponse errorResponse = objectMapper.readValue(content, ErrorResponse.class);

        assertThat(errorResponse.code()).isEqualTo(ErrorCode.UNAUTHENTICATED.name());
        assertThat(errorResponse.message()).isEqualTo(ErrorCode.UNAUTHENTICATED.getDefaultMessage());
        assertThat(errorResponse.path()).isEqualTo("/api/v1/auth/me");
        assertThat(errorResponse.timestamp()).isEqualTo(NOW);
        assertThat(errorResponse.traceId()).isNotBlank();
    }
}

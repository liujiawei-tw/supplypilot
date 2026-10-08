package com.supplypilot.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.exception.ErrorResponse;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class CustomAccessDeniedHandlerTests {

    @Test
    void writes403StandardErrorResponse() throws Exception {
        Instant now = Instant.parse("2026-10-09T00:00:00Z");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CustomAccessDeniedHandler handler = new CustomAccessDeniedHandler(
                objectMapper, Clock.fixed(now, ZoneOffset.UTC)
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("forbidden"));

        ErrorResponse errorResponse = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(errorResponse.code()).isEqualTo(ErrorCode.ACCESS_DENIED.name());
        assertThat(errorResponse.message()).isEqualTo("You do not have permission to perform this operation.");
        assertThat(errorResponse.path()).isEqualTo("/api/v1/users");
        assertThat(errorResponse.traceId()).isNotBlank();
        assertThat(errorResponse.timestamp()).isEqualTo(now);
    }
}

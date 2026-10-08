package com.supplypilot.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.exception.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Handles 401 Unauthorized errors in the Spring Security filter chain.
 *
 * <p>Emits a response following the standard {@link ErrorResponse} contract:
 * code = UNAUTHENTICATED, message = "Authentication is required."
 */
@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(CustomAuthenticationEntryPoint.class);
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CustomAuthenticationEntryPoint(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        String traceId = resolveTraceId();
        String path = request.getRequestURI();

        log.warn("Unauthenticated request: path={}, traceId={}, reason={}",
                path, traceId, authException.getMessage());

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ErrorResponse errorResponse = ErrorResponse.of(
                ErrorCode.UNAUTHENTICATED,
                ErrorCode.UNAUTHENTICATED.getDefaultMessage(),
                traceId,
                Instant.now(clock),
                path
        );

        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    private String resolveTraceId() {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        return (traceId == null || traceId.isBlank()) ? UUID.randomUUID().toString() : traceId;
    }
}

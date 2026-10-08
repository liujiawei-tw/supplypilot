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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Writes the standard JSON response for authenticated callers without sufficient authority. */
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomAccessDeniedHandler.class);
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CustomAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        String traceId = resolveTraceId();
        String path = request.getRequestURI();
        log.warn("Access denied: path={}, traceId={}", path, traceId);

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ErrorResponse.of(
                ErrorCode.ACCESS_DENIED,
                ErrorCode.ACCESS_DENIED.getDefaultMessage(),
                traceId,
                Instant.now(clock),
                path
        )));
    }

    private String resolveTraceId() {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        return (traceId == null || traceId.isBlank()) ? UUID.randomUUID().toString() : traceId;
    }
}

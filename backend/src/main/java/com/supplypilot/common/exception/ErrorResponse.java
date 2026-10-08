package com.supplypilot.common.exception;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        String code,
        String message,
        String traceId,
        Instant timestamp,
        String path,
        List<FieldErrorResponse> fieldErrors
) {

    public ErrorResponse {
        fieldErrors = fieldErrors == null ? null : List.copyOf(fieldErrors);
    }

    public static ErrorResponse of(
            ErrorCode errorCode,
            String message,
            String traceId,
            Instant timestamp,
            String path
    ) {
        return new ErrorResponse(errorCode.name(), message, traceId, timestamp, path, null);
    }
}

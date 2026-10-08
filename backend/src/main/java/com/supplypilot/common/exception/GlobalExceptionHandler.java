package com.supplypilot.common.exception;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final Clock clock;

    public GlobalExceptionHandler() {
        this(Clock.systemUTC());
    }

    GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        HttpStatus status = ErrorHttpStatusMapper.toHttpStatus(errorCode);
        String traceId = traceId();

        log.warn("Business request failed: code={}, path={}, traceId={}",
                errorCode, request.getRequestURI(), traceId);

        return ResponseEntity.status(status).body(errorResponse(
                errorCode,
                exception.getMessage(),
                traceId,
                request.getRequestURI()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String traceId = traceId();
        List<FieldErrorResponse> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorResponse(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldErrorResponse::field)
                        .thenComparing(FieldErrorResponse::message))
                .toList();

        log.warn("Request validation failed: path={}, traceId={}", request.getRequestURI(), traceId);

        return ResponseEntity.badRequest().body(new ErrorResponse(
                ErrorCode.VALIDATION_FAILED.name(),
                ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                traceId,
                Instant.now(clock),
                request.getRequestURI(),
                fieldErrors
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        String traceId = traceId();
        log.warn("Request constraint validation failed: path={}, traceId={}",
                request.getRequestURI(), traceId);

        return ResponseEntity.badRequest().body(errorResponse(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                traceId,
                request.getRequestURI()
        ));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(
            ObjectOptimisticLockingFailureException exception,
            HttpServletRequest request
    ) {
        return conflictResponse(ErrorCode.CONCURRENT_MODIFICATION, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        return conflictResponse(ErrorCode.DATA_INTEGRITY_VIOLATION, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        String traceId = traceId();
        log.error("Unexpected request failure: path={}, traceId={}",
                request.getRequestURI(), traceId, exception);

        return ResponseEntity.internalServerError().body(errorResponse(
                ErrorCode.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_SERVER_ERROR.getDefaultMessage(),
                traceId,
                request.getRequestURI()
        ));
    }

    private ResponseEntity<ErrorResponse> conflictResponse(
            ErrorCode errorCode,
            HttpServletRequest request
    ) {
        String traceId = traceId();
        log.warn("Request conflict: code={}, path={}, traceId={}",
                errorCode, request.getRequestURI(), traceId);

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse(
                errorCode,
                errorCode.getDefaultMessage(),
                traceId,
                request.getRequestURI()
        ));
    }

    private ErrorResponse errorResponse(
            ErrorCode errorCode,
            String message,
            String traceId,
            String path
    ) {
        return ErrorResponse.of(errorCode, message, traceId, Instant.now(clock), path);
    }

    private String traceId() {
        String existingTraceId = MDC.get(TRACE_ID_MDC_KEY);
        return existingTraceId == null || existingTraceId.isBlank()
                ? UUID.randomUUID().toString()
                : existingTraceId;
    }
}

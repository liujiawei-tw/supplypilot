package com.supplypilot.document.semantic;

import java.time.Duration;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;

/** Provider failure metadata for bounded background retries; the provider response body is never exposed. */
public final class SemanticProviderException extends BusinessException {
    private final boolean retryable;
    private final Duration retryAfter;
    private final String safeFailureCode;

    public SemanticProviderException(ErrorCode errorCode, boolean retryable, Duration retryAfter,
                                    String safeFailureCode) {
        super(errorCode);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
        this.safeFailureCode = safeFailureCode;
    }

    public boolean retryable() { return retryable; }
    public Duration retryAfter() { return retryAfter; }
    public String safeFailureCode() { return safeFailureCode; }
}

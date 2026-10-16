package com.supplypilot.ai.client;

import com.supplypilot.common.exception.ErrorCode;

public class AiProviderException extends RuntimeException {
    private final ErrorCode errorCode;
    private final AiFailureReason failureReason;

    public AiProviderException(ErrorCode errorCode) {
        this(errorCode, (Throwable) null);
    }

    public AiProviderException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, cause, AiFailureReason.OTHER_PROVIDER_FAILURE);
    }

    public AiProviderException(ErrorCode errorCode, AiFailureReason failureReason) {
        this(errorCode, null, failureReason);
    }

    public AiProviderException(ErrorCode errorCode, Throwable cause, AiFailureReason failureReason) {
        super(errorCode.name(), cause);
        if (errorCode != ErrorCode.AI_PROVIDER_UNAVAILABLE
                && errorCode != ErrorCode.AI_TIMEOUT
                && errorCode != ErrorCode.AI_INVALID_RESPONSE) {
            throw new IllegalArgumentException("Only AI provider error codes are allowed.");
        }
        this.errorCode = errorCode;
        this.failureReason = failureReason == null ? AiFailureReason.OTHER_PROVIDER_FAILURE : failureReason;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public AiFailureReason getFailureReason() {
        return failureReason;
    }
}

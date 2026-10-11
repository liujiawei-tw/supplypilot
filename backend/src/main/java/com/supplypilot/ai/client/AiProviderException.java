package com.supplypilot.ai.client;

import com.supplypilot.common.exception.ErrorCode;

public class AiProviderException extends RuntimeException {
    private final ErrorCode errorCode;

    public AiProviderException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public AiProviderException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.name(), cause);
        if (errorCode != ErrorCode.AI_PROVIDER_UNAVAILABLE
                && errorCode != ErrorCode.AI_TIMEOUT
                && errorCode != ErrorCode.AI_INVALID_RESPONSE) {
            throw new IllegalArgumentException("Only AI provider error codes are allowed.");
        }
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

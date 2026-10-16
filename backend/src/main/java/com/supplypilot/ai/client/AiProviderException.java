package com.supplypilot.ai.client;

import com.supplypilot.common.exception.ErrorCode;

public class AiProviderException extends RuntimeException {
    private final ErrorCode errorCode;
    private final AiFailureReason failureReason;
    private final AiFailureDetail failureDetail;

    public AiProviderException(ErrorCode errorCode) {
        this(errorCode, (Throwable) null);
    }

    public AiProviderException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, cause, AiFailureReason.OTHER_PROVIDER_FAILURE);
    }

    public AiProviderException(ErrorCode errorCode, AiFailureReason failureReason) {
        this(errorCode, null, failureReason, null);
    }

    public AiProviderException(ErrorCode errorCode, AiFailureReason failureReason,
                               AiFailureDetail failureDetail) {
        this(errorCode, null, failureReason, failureDetail);
    }

    public AiProviderException(ErrorCode errorCode, Throwable cause, AiFailureReason failureReason) {
        this(errorCode, cause, failureReason, null);
    }

    public AiProviderException(ErrorCode errorCode, Throwable cause, AiFailureReason failureReason,
                               AiFailureDetail failureDetail) {
        super(errorCode.name(), cause);
        if (errorCode != ErrorCode.AI_PROVIDER_UNAVAILABLE
                && errorCode != ErrorCode.AI_TIMEOUT
                && errorCode != ErrorCode.AI_INVALID_RESPONSE) {
            throw new IllegalArgumentException("Only AI provider error codes are allowed.");
        }
        this.errorCode = errorCode;
        this.failureReason = failureReason == null ? AiFailureReason.OTHER_PROVIDER_FAILURE : failureReason;
        this.failureDetail = failureDetail;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public AiFailureReason getFailureReason() {
        return failureReason;
    }

    public AiFailureDetail getFailureDetail() {
        return failureDetail;
    }
}

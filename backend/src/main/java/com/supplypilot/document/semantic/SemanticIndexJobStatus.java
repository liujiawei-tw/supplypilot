package com.supplypilot.document.semantic;

public enum SemanticIndexJobStatus {
    QUEUED,
    PROCESSING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    SUPERSEDED
}

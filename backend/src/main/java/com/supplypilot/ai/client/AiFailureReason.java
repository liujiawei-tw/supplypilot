package com.supplypilot.ai.client;

/** Fixed, non-sensitive diagnostic categories for provider and tool-flow failures. */
public enum AiFailureReason {
    MODEL_CALL_LIMIT,
    TOOL_CALL_LIMIT,
    UNKNOWN_OR_FORBIDDEN_TOOL,
    UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER,
    TOOL_EXECUTION_FAILURE,
    MISSING_FINAL_MODEL_RESPONSE,
    INVALID_FINAL_RESPONSE_STRUCTURE,
    NO_TOOL_EXECUTION,
    OTHER_PROVIDER_FAILURE
}

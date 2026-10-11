package com.supplypilot.ai.service;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class AiResponseGuard {
    private final ObjectMapper objectMapper;

    public AiResponseGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * A successful response must be the exact structured value returned by the one tool that
     * actually executed. Equality protects field ownership, identifiers, units, currencies,
     * source provenance and values; merely containing the same set of numbers is not sufficient.
     */
    public AiToolResult verifyDirectToolResult(String payload, List<AiToolResult> executions) {
        if (payload == null || payload.isBlank() || executions.size() != 1) {
            throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE);
        }
        try {
            AiToolResult returned = objectMapper.readValue(payload, AiToolResult.class);
            AiToolResult executed = executions.getFirst();
            if (!executed.equals(returned)
                    || returned.tool() == null
                    || returned.status() == null
                    || returned.sources() == null
                    || (returned.status() == AiQueryStatus.ANSWERED
                        && (returned.trustedFacts() == null || returned.trustedFacts().isBlank()))) {
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE);
            }
            return returned;
        } catch (JsonProcessingException exception) {
            throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE, exception);
        }
    }
}

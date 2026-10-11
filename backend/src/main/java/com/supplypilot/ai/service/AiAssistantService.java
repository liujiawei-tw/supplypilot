package com.supplypilot.ai.service;

import java.util.List;

import com.supplypilot.ai.client.AiProviderClient;
import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.client.AiProviderRequest;
import com.supplypilot.ai.dto.AiQueryResponse;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.ai.tool.AiToolRegistry;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class AiAssistantService {
    private final AiQueryPlanner planner;
    private final AiToolRegistry tools;
    private final AiProviderClient provider;
    private final AiResponseGuard responseGuard;
    private final CurrentUserProvider currentUserProvider;

    public AiAssistantService(
            AiQueryPlanner planner,
            AiToolRegistry tools,
            AiProviderClient provider,
            AiResponseGuard responseGuard,
            CurrentUserProvider currentUserProvider
    ) {
        this.planner = planner;
        this.tools = tools;
        this.provider = provider;
        this.responseGuard = responseGuard;
        this.currentUserProvider = currentUserProvider;
    }

    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public AiQueryResponse query(String message) {
        // Fail closed unless the caller has a real AuthenticatedUser in the current SecurityContext.
        currentUserProvider.getCurrentUserId();
        var plan = planner.plan(message);
        var result = tools.execute(plan);
        if (result.status() == AiQueryStatus.INSUFFICIENT_EVIDENCE) {
            return new AiQueryResponse(result.status(), null, List.of(result.tool()), result.sources());
        }

        String answer;
        try {
            answer = provider.generateAnswer(new AiProviderRequest(
                    message, result.tool(), result.trustedFacts(), result.sources()));
        } catch (AiProviderException exception) {
            throw new BusinessException(exception.getErrorCode());
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        if (answer == null || answer.isBlank()
                || !responseGuard.containsOnlyTrustedNumbers(answer, result.trustedFacts())) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        return new AiQueryResponse(AiQueryStatus.ANSWERED, answer,
                List.of(result.tool()), result.sources());
    }
}

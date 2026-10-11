package com.supplypilot.ai.service;

import java.util.List;

import com.supplypilot.ai.client.AiProviderClient;
import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.client.AiProviderRequest;
import com.supplypilot.ai.dto.AiQueryResponse;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

@Service
public class AiAssistantService {
    private final AiQueryPlanner planner;
    private final AiProviderClient provider;
    private final CurrentUserProvider currentUserProvider;

    public AiAssistantService(
            AiQueryPlanner planner,
            AiProviderClient provider,
            CurrentUserProvider currentUserProvider
    ) {
        this.planner = planner;
        this.provider = provider;
        this.currentUserProvider = currentUserProvider;
    }

    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public AiQueryResponse query(String message) {
        // Fail closed unless the caller has a real AuthenticatedUser in the current SecurityContext.
        currentUserProvider.getCurrentUserId();
        String validated = planner.validate(message);
        String missing = planner.missingIdentifier(validated);
        if (missing != null) {
            return new AiQueryResponse(AiQueryStatus.NEEDS_CLARIFICATION, missing,
                    List.of(), List.of(), "BACKEND_CLARIFICATION");
        }
        var result = queryProvider(validated);
        var executions = result.results();
        boolean answered = executions.stream().anyMatch(item -> item.status() == AiQueryStatus.ANSWERED);
        return new AiQueryResponse(answered ? AiQueryStatus.ANSWERED : AiQueryStatus.INSUFFICIENT_EVIDENCE,
                answered ? result.answer() : null,
                executions.stream().map(item -> item.tool()).toList(),
                executions.stream().flatMap(item -> item.sources().stream()).toList(),
                answered ? result.answerSource() : null);
    }

    private com.supplypilot.ai.client.AiProviderResponse queryProvider(String message) {
        try {
            return provider.query(new AiProviderRequest(message));
        } catch (AiProviderException exception) {
            throw new BusinessException(exception.getErrorCode());
        } catch (BusinessException exception) {
            throw exception;
        } catch (AccessDeniedException | AuthenticationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }
}

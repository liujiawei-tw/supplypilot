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
    private final AiAnswerEvidencePolicy evidencePolicy;

    public AiAssistantService(
            AiQueryPlanner planner,
            AiProviderClient provider,
            CurrentUserProvider currentUserProvider,
            AiAnswerEvidencePolicy evidencePolicy
    ) {
        this.planner = planner;
        this.provider = provider;
        this.currentUserProvider = currentUserProvider;
        this.evidencePolicy = evidencePolicy;
    }

    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public AiQueryResponse query(String message) {
        // Fail closed unless the caller has a real AuthenticatedUser in the current SecurityContext.
        currentUserProvider.getCurrentUserId();
        String validated = planner.validate(message);
        // A missing identifier is a clarification only when AI is actually configured.
        if (!provider.isConfigured()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        String missing = planner.missingIdentifier(validated);
        if (missing != null) {
            return new AiQueryResponse(AiQueryStatus.NEEDS_CLARIFICATION, missing,
                    List.of(), List.of(), "BACKEND_CLARIFICATION");
        }
        var result = queryProvider(validated);
        var executions = result.results();
        var supporting = evidencePolicy.supportingResults(validated, executions);
        boolean answered = !supporting.isEmpty();
        boolean untouched = supporting.equals(executions) && result.answer() != null && !result.answer().isBlank();
        String answer = !answered ? null : untouched ? result.answer() : supporting.stream()
                .map(item -> item.trustedFacts()).collect(java.util.stream.Collectors.joining("\n"));
        return new AiQueryResponse(answered ? AiQueryStatus.ANSWERED : AiQueryStatus.INSUFFICIENT_EVIDENCE,
                answer,
                executions.stream().map(item -> item.tool()).toList(),
                supporting.stream().flatMap(item -> item.sources().stream()).toList(),
                answered ? untouched ? result.answerSource() : "BACKEND_FACTS" : null);
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

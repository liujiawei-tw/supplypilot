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
        planner.validate(message);
        var result = queryProvider(message);
        if (result.status() == AiQueryStatus.INSUFFICIENT_EVIDENCE) {
            return new AiQueryResponse(result.status(), null, List.of(result.tool()), result.sources());
        }
        return new AiQueryResponse(AiQueryStatus.ANSWERED, result.trustedFacts(),
                List.of(result.tool()), result.sources());
    }

    private com.supplypilot.ai.tool.AiToolResult queryProvider(String message) {
        try {
            return provider.query(new AiProviderRequest(message)).result();
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

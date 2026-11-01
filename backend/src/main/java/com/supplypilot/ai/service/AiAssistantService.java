package com.supplypilot.ai.service;

import java.util.List;

import com.supplypilot.ai.client.AiProviderClient;
import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.client.AiProviderRequest;
import com.supplypilot.ai.dto.AiQueryResponse;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiAnswerData;
import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.ai.tool.AiToolResult;
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
    private final AiToolResultPolicy toolResultPolicy;

    public AiAssistantService(
            AiQueryPlanner planner,
            AiProviderClient provider,
            CurrentUserProvider currentUserProvider,
            AiToolResultPolicy toolResultPolicy
    ) {
        this.planner = planner;
        this.provider = provider;
        this.currentUserProvider = currentUserProvider;
        this.toolResultPolicy = toolResultPolicy;
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
        var requestedTools = result.requestedTools();
        var supporting = toolResultPolicy.supportingResults(validated, requestedTools, executions);
        if (supporting.isEmpty()) {
            var partial = toolResultPolicy.partialResults(validated, requestedTools, executions);
            List<String> incompleteNotes = toolResultPolicy.incompleteNotes(validated, requestedTools,
                    executions, partial);
            if (!partial.isEmpty()) {
                AiAnswerData partialDetails = AiAnswerData.merge(partial.stream().map(item -> item.details()).toList());
                List<String> notes = java.util.stream.Stream.concat(partialDetails.notes().stream(),
                                incompleteNotes.stream())
                        .distinct().toList();
                partialDetails = new AiAnswerData(partialDetails.inventory(), partialDetails.quotes(),
                        partialDetails.averagePrices(), partialDetails.suppliers(), partialDetails.purchaseOrders(),
                        partialDetails.candidates(), partialDetails.unmatchedIdentifiers(), notes,
                        partialDetails.purchaseOrderTotalCount(), partialDetails.queriedMaterialCodes());
                String partialFacts = partial.stream().map(AiToolResult::trustedFacts)
                        .filter(java.util.Objects::nonNull).filter(value -> !value.isBlank())
                        .collect(java.util.stream.Collectors.joining("\n"));
                List<com.supplypilot.ai.dto.AiSourceResponse> partialSources = partial.stream()
                        .flatMap(item -> item.sources().stream()).distinct().toList();
                List<com.supplypilot.ai.dto.AiToolType> partialTools = partial.stream()
                        .map(AiToolResult::tool).distinct().toList();
                return new AiQueryResponse(AiQueryStatus.INSUFFICIENT_EVIDENCE,
                        partialFacts.isBlank() ? null : partialFacts, partialTools, partialSources,
                        partialFacts.isBlank() ? null : "BACKEND_FACTS", partialDetails, null);
            }
            if (!incompleteNotes.isEmpty()) {
                AiAnswerData statusDetails = new AiAnswerData(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), incompleteNotes);
                return new AiQueryResponse(AiQueryStatus.INSUFFICIENT_EVIDENCE, null,
                        List.of(), List.of(), null, statusDetails, null);
            }
            return new AiQueryResponse(AiQueryStatus.INSUFFICIENT_EVIDENCE, null,
                    List.of(), List.of(), null, AiAnswerData.empty());
        }
        AiAnswerData details = AiAnswerData.merge(supporting.stream().map(item -> item.details()).toList());
        List<com.supplypilot.ai.dto.AiSourceResponse> sources = supporting.stream()
                .flatMap(item -> item.sources().stream()).distinct().toList();
        List<com.supplypilot.ai.dto.AiToolType> tools = supporting.stream().map(item -> item.tool()).distinct().toList();
        boolean clarification = supporting.stream().anyMatch(item -> item.status() == AiQueryStatus.NEEDS_CLARIFICATION);
        boolean anyAnswered = supporting.stream().anyMatch(item -> item.status() == AiQueryStatus.ANSWERED);
        boolean anyNotFound = supporting.stream().anyMatch(item -> item.status() == AiQueryStatus.NOT_FOUND);
        AiQueryStatus status = clarification ? AiQueryStatus.NEEDS_CLARIFICATION
                : anyAnswered ? AiQueryStatus.ANSWERED
                : anyNotFound ? AiQueryStatus.NOT_FOUND : AiQueryStatus.INSUFFICIENT_EVIDENCE;
        boolean untouched = supporting.equals(executions) && result.answer() != null && !result.answer().isBlank();
        String answer = status == AiQueryStatus.INSUFFICIENT_EVIDENCE ? null
                : untouched ? result.answer() : supporting.stream().map(item -> item.trustedFacts())
                    .filter(java.util.Objects::nonNull).filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
        if (status == AiQueryStatus.ANSWERED && anyNotFound) {
            String missingFacts = supporting.stream().filter(item -> item.status() == AiQueryStatus.NOT_FOUND)
                    .map(item -> item.trustedFacts()).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (!missingFacts.isBlank() && (answer == null || !answer.contains(missingFacts))) {
                answer = (answer == null ? "" : answer + "\n") + missingFacts;
            }
        }
        String summary = status == AiQueryStatus.ANSWERED && untouched ? result.summary() : null;
        String answerSource = status != AiQueryStatus.ANSWERED ? null
                : summary == null ? "BACKEND_FACTS" : "MODEL_EXPLANATION";
        return new AiQueryResponse(status, answer, tools, sources, answerSource, details, summary);
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

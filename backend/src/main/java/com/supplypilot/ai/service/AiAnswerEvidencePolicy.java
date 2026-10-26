package com.supplypilot.ai.service;

import java.util.List;
import java.util.Set;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.ai.tool.AiQuestionIntent;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.document.rag.EvidenceValidator;
import org.springframework.stereotype.Component;

/** Checks whether a trusted tool result addresses the question, beyond merely having run successfully. */
@Component
public class AiAnswerEvidencePolicy {
    private final EvidenceValidator evidenceValidator;

    public AiAnswerEvidencePolicy(EvidenceValidator evidenceValidator) {
        this.evidenceValidator = evidenceValidator;
    }

    public List<AiToolResult> supportingResults(String question, List<AiToolResult> executions) {
        Set<AiToolType> requested = AiQuestionIntent.requestedTools(question);
        if (requested.isEmpty()) {
            return executions.stream().filter(this::answered).toList();
        }
        List<AiToolResult> supporting = executions.stream()
                .filter(result -> requested.stream().anyMatch(tool -> AiQuestionIntent.satisfies(tool, result.tool())))
                .filter(result -> result.status() == AiQueryStatus.ANSWERED
                        || result.status() == AiQueryStatus.NOT_FOUND
                        || result.status() == AiQueryStatus.NEEDS_CLARIFICATION)
                .filter(result -> result.tool() != AiToolType.SEARCH_DOCUMENTS || supportedDocument(question, result))
                .toList();
        // A compound request is not complete when only one of its explicitly requested facts is present.
        if (requested.stream().anyMatch(tool -> supporting.stream()
                .noneMatch(result -> AiQuestionIntent.satisfies(tool, result.tool())))
                || executions.stream().anyMatch(result -> result.status() == AiQueryStatus.INSUFFICIENT_EVIDENCE
                        && requested.stream().anyMatch(tool -> AiQuestionIntent.satisfies(tool, result.tool())))) {
            return List.of();
        }
        if (requested.contains(AiToolType.QUERY_INVENTORY)) {
            Set<String> expectedCodes = Set.copyOf(new com.supplypilot.ai.tool.AiQueryPlanner().materialCodes(question));
            if (!expectedCodes.isEmpty()) {
                Set<String> observedCodes = supporting.stream()
                        .filter(result -> result.tool() == AiToolType.QUERY_INVENTORY)
                        .flatMap(result -> java.util.stream.Stream.concat(
                                result.details().inventory().stream().map(item -> item.materialCode()),
                                result.details().unmatchedIdentifiers().stream()))
                        .map(value -> value == null ? "" : value.toUpperCase(java.util.Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());
                boolean allRequestedCodesCovered = expectedCodes.stream().allMatch(expected -> observedCodes.stream()
                        .anyMatch(observed -> observed.equals(expected) || observed.startsWith(expected + "（")));
                if (!allRequestedCodesCovered) return List.of();
            }
        }
        if (requested.contains(AiToolType.QUERY_SUPPLIER)) {
            Set<String> expectedCodes = Set.copyOf(new com.supplypilot.ai.tool.AiQueryPlanner().supplierCodes(question));
            if (!expectedCodes.isEmpty()) {
                Set<String> observedCodes = supporting.stream()
                        .filter(result -> result.tool() == AiToolType.QUERY_SUPPLIER)
                        .flatMap(result -> java.util.stream.Stream.concat(
                                result.details().suppliers().stream().map(item -> item.supplierCode()),
                                result.details().unmatchedIdentifiers().stream()))
                        .filter(java.util.Objects::nonNull)
                        .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());
                if (!expectedCodes.equals(observedCodes.stream().filter(expectedCodes::contains)
                        .collect(java.util.stream.Collectors.toSet()))) return List.of();
            }
        }
        return supporting;
    }

    /** Returns individually supported results when a compound request cannot be completed in full. */
    public List<AiToolResult> partialResults(String question, List<AiToolResult> executions) {
        Set<AiToolType> requested = AiQuestionIntent.requestedTools(question);
        return executions.stream()
                .filter(result -> requested.isEmpty() ? result.status() == AiQueryStatus.ANSWERED
                        : requested.stream().anyMatch(tool -> AiQuestionIntent.satisfies(tool, result.tool())))
                .filter(result -> result.status() == AiQueryStatus.ANSWERED
                        || result.status() == AiQueryStatus.NOT_FOUND
                        || result.status() == AiQueryStatus.NEEDS_CLARIFICATION)
                .filter(result -> result.tool() != AiToolType.SEARCH_DOCUMENTS || supportedDocument(question, result))
                .toList();
    }

    /** Explains incomplete parts without conflating document gaps, business gaps, and absent tool results. */
    public List<String> incompleteNotes(String question, List<AiToolResult> executions,
                                        List<AiToolResult> partialResults) {
        Set<AiToolType> requested = AiQuestionIntent.requestedTools(question);
        List<String> notes = new java.util.ArrayList<>();
        for (AiToolType tool : requested) {
            boolean hasSupportedResult = partialResults.stream()
                    .anyMatch(result -> AiQuestionIntent.satisfies(tool, result.tool()));
            if (hasSupportedResult) continue;
            boolean insufficient = executions.stream().anyMatch(result -> result.status() == AiQueryStatus.INSUFFICIENT_EVIDENCE
                    && AiQuestionIntent.satisfies(tool, result.tool()));
            if (insufficient && tool == AiToolType.SEARCH_DOCUMENTS) {
                notes.add("文件檢索證據不足，無法提供文件結論。");
            } else if (insufficient) {
                notes.add(toolLabel(tool) + "未取得足夠的業務查詢證據。");
            } else {
                notes.add("尚未取得可信的" + toolLabel(tool) + "結果。");
            }
        }
        Set<String> expectedMaterials = Set.copyOf(new com.supplypilot.ai.tool.AiQueryPlanner().materialCodes(question));
        if (requested.contains(AiToolType.QUERY_INVENTORY) && !expectedMaterials.isEmpty()) {
            Set<String> observed = partialResults.stream().filter(result -> result.tool() == AiToolType.QUERY_INVENTORY)
                    .flatMap(result -> java.util.stream.Stream.concat(
                            result.details().inventory().stream().map(item -> item.materialCode()),
                            result.details().unmatchedIdentifiers().stream()))
                    .map(value -> value == null ? "" : value.toUpperCase(java.util.Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            List<String> missing = expectedMaterials.stream().filter(expected -> observed.stream()
                    .noneMatch(value -> value.equals(expected) || value.startsWith(expected + "（"))).toList();
            if (!missing.isEmpty()) notes.add("尚未取得以下物料代碼的結果：" + String.join("、", missing) + "。");
        }
        Set<String> expectedSuppliers = Set.copyOf(new com.supplypilot.ai.tool.AiQueryPlanner().supplierCodes(question));
        if (requested.contains(AiToolType.QUERY_SUPPLIER) && !expectedSuppliers.isEmpty()) {
            Set<String> observed = partialResults.stream().filter(result -> result.tool() == AiToolType.QUERY_SUPPLIER)
                    .flatMap(result -> java.util.stream.Stream.concat(
                            result.details().suppliers().stream().map(item -> item.supplierCode()),
                            result.details().unmatchedIdentifiers().stream()))
                    .filter(java.util.Objects::nonNull)
                    .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            List<String> missing = expectedSuppliers.stream().filter(expected -> !observed.contains(expected)).toList();
            if (!missing.isEmpty()) notes.add("尚未取得以下供應商代碼的結果：" + String.join("、", missing) + "。");
        }
        return notes.stream().distinct().toList();
    }

    private String toolLabel(AiToolType tool) {
        return switch (tool) {
            case SEARCH_DOCUMENTS -> "文件檢索";
            case QUERY_INVENTORY -> "庫存查詢";
            case QUERY_SUPPLIER -> "供應商主檔查詢";
            case QUERY_SUPPLIER_QUOTES -> "供應商報價查詢";
            case QUERY_AVERAGE_PRICE -> "平均報價查詢";
            case QUERY_PURCHASE_ORDER -> "採購單查詢";
            case QUERY_OPEN_PO -> "未收貨採購單查詢";
            case QUERY_RECOMMENDATION, EXPLAIN_RECOMMENDATION -> "採購建議查詢";
            case QUERY_SUPPLIER_RANKING -> "供應商排名查詢";
        };
    }

    private boolean answered(AiToolResult result) {
        return result != null && result.status() == AiQueryStatus.ANSWERED
                && result.trustedFacts() != null && !result.trustedFacts().isBlank()
                && result.sources() != null;
    }

    private boolean supportedDocument(String question, AiToolResult result) {
        return !result.sources().isEmpty() && result.sources().stream()
                .allMatch(source -> validDocumentSource(question, source));
    }

    private boolean validDocumentSource(String question, AiSourceResponse source) {
        return source != null && "DOCUMENT".equals(source.resourceType())
                && source.resourceId() != null && !source.resourceId().isBlank()
                && source.version() != null && !source.version().isBlank()
                && source.chunkIndex() != null && source.chunkIndex() >= 0
                && source.startOffset() != null && source.startOffset() >= 0
                && source.endOffset() != null && source.endOffset() > source.startOffset()
                && source.excerpt() != null
                && source.excerpt().length() == source.endOffset() - source.startOffset()
                && !evidenceValidator.supportedExcerpt(question, source.excerpt()).isBlank();
    }
}

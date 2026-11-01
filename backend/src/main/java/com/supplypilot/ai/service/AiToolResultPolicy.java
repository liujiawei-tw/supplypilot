package com.supplypilot.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.ai.tool.AiQuestionIntent;
import com.supplypilot.ai.tool.AiToolResult;
import org.springframework.stereotype.Component;

/** Applies domain coverage rules to structured tool results; document evidence is handled by RAG retrieval. */
@Component
public class AiToolResultPolicy {
    private final AiQueryPlanner planner;

    public AiToolResultPolicy(AiQueryPlanner planner) {
        this.planner = planner;
    }

    public List<AiToolResult> supportingResults(String question, Set<AiToolType> requested,
                                               List<AiToolResult> executions) {
        if (requested == null || requested.isEmpty()) return List.of();
        List<AiToolResult> supporting = acceptedResults(requested, executions);

        // A compound request is not complete when only one of its explicitly requested facts is present.
        if (requested.stream().anyMatch(tool -> supporting.stream()
                .noneMatch(result -> AiQuestionIntent.satisfies(tool, result.tool())))
                || executions.stream().anyMatch(result -> result.status() == AiQueryStatus.INSUFFICIENT_EVIDENCE
                        && requested.stream().anyMatch(tool -> AiQuestionIntent.satisfies(tool, result.tool())))) {
            return List.of();
        }
        for (AiToolType tool : requested) {
            if (requiresMaterialCoverage(tool) && !allRequestedMaterialsCovered(tool, question, supporting)) {
                return List.of();
            }
        }
        if (requested.contains(AiToolType.QUERY_SUPPLIER)) {
            Set<String> expectedCodes = Set.copyOf(planner.supplierCodes(question));
            if (!expectedCodes.isEmpty()) {
                Set<String> observedCodes = supporting.stream()
                        .filter(result -> result.tool() == AiToolType.QUERY_SUPPLIER)
                        .flatMap(result -> Stream.concat(
                                result.details().suppliers().stream().map(item -> item.supplierCode()),
                                result.details().unmatchedIdentifiers().stream()))
                        .filter(Objects::nonNull)
                        .map(value -> value.toUpperCase(Locale.ROOT))
                        .collect(Collectors.toSet());
                if (!expectedCodes.equals(observedCodes.stream().filter(expectedCodes::contains)
                        .collect(Collectors.toSet()))) return List.of();
            }
        }
        return supporting;
    }

    /** Returns individually successful requested tool results if a compound request is incomplete. */
    public List<AiToolResult> partialResults(String question, Set<AiToolType> requested,
                                             List<AiToolResult> executions) {
        if (requested == null || requested.isEmpty()) return List.of();
        return acceptedResults(requested, executions);
    }

    /** Explains incomplete tool/domain coverage without making document evidence decisions. */
    public List<String> incompleteNotes(String question, Set<AiToolType> requested,
                                        List<AiToolResult> executions,
                                        List<AiToolResult> partialResults) {
        if (requested == null || requested.isEmpty()) return List.of();
        List<String> notes = new ArrayList<>();
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

        Set<String> expectedMaterials = Set.copyOf(planner.materialCodes(question));
        for (AiToolType tool : requested) {
            if (!requiresMaterialCoverage(tool) || expectedMaterials.isEmpty()) continue;
            Set<String> observed = observedMaterialCodes(tool, partialResults);
            List<String> missing = expectedMaterials.stream().filter(expected -> observed.stream()
                    .noneMatch(value -> matchesMaterialCode(expected, value))).toList();
            if (!missing.isEmpty()) notes.add("尚未取得以下" + toolLabel(tool) + "物料結果："
                    + String.join("、", missing) + "。");
        }

        Set<String> expectedSuppliers = Set.copyOf(planner.supplierCodes(question));
        if (requested.contains(AiToolType.QUERY_SUPPLIER) && !expectedSuppliers.isEmpty()) {
            Set<String> observed = partialResults.stream().filter(result -> result.tool() == AiToolType.QUERY_SUPPLIER)
                    .flatMap(result -> Stream.concat(
                            result.details().suppliers().stream().map(item -> item.supplierCode()),
                            result.details().unmatchedIdentifiers().stream()))
                    .filter(Objects::nonNull)
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            List<String> missing = expectedSuppliers.stream().filter(expected -> !observed.contains(expected)).toList();
            if (!missing.isEmpty()) notes.add("尚未取得以下供應商代碼的結果：" + String.join("、", missing) + "。");
        }
        return notes.stream().distinct().toList();
    }

    private List<AiToolResult> acceptedResults(Set<AiToolType> requested, List<AiToolResult> executions) {
        return executions.stream()
                .filter(result -> requested.stream().anyMatch(tool -> AiQuestionIntent.satisfies(tool, result.tool())))
                .filter(result -> result.status() == AiQueryStatus.ANSWERED
                        || result.status() == AiQueryStatus.NOT_FOUND
                        || result.status() == AiQueryStatus.NEEDS_CLARIFICATION)
                .toList();
    }

    private boolean requiresMaterialCoverage(AiToolType tool) {
        return tool == AiToolType.QUERY_INVENTORY || tool == AiToolType.QUERY_SUPPLIER_QUOTES
                || tool == AiToolType.QUERY_AVERAGE_PRICE || tool == AiToolType.QUERY_OPEN_PO;
    }

    private boolean allRequestedMaterialsCovered(AiToolType tool, String question, List<AiToolResult> results) {
        Set<String> expected = Set.copyOf(planner.materialCodes(question));
        if (expected.isEmpty()) return true;
        Set<String> observed = observedMaterialCodes(tool, results);
        return expected.stream().allMatch(code -> observed.stream().anyMatch(value -> matchesMaterialCode(code, value)));
    }

    private Set<String> observedMaterialCodes(AiToolType tool, List<AiToolResult> results) {
        return results.stream().filter(result -> result.tool() == tool)
                .flatMap(result -> {
                    Stream<String> codes = result.details().queriedMaterialCodes().stream();
                    codes = Stream.concat(codes, result.details().unmatchedIdentifiers().stream());
                    return switch (tool) {
                        case QUERY_INVENTORY -> Stream.concat(codes,
                                result.details().inventory().stream().map(item -> item.materialCode()));
                        case QUERY_SUPPLIER_QUOTES -> Stream.concat(codes,
                                result.details().quotes().stream().map(item -> item.materialCode()));
                        case QUERY_AVERAGE_PRICE -> Stream.concat(codes,
                                result.details().averagePrices().stream().map(item -> item.materialCode()));
                        case QUERY_OPEN_PO -> Stream.concat(codes,
                                result.details().purchaseOrders().stream().flatMap(order -> order.items().stream())
                                        .map(item -> item.materialCode()));
                        default -> codes;
                    };
                })
                .filter(Objects::nonNull)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private boolean matchesMaterialCode(String expected, String observed) {
        return observed.equals(expected) || observed.startsWith(expected + "（");
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
}

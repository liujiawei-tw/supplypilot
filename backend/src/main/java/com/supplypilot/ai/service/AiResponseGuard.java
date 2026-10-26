package com.supplypilot.ai.service;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.client.AiFailureReason;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class AiResponseGuard {
    private static final String ANSWERED_SUMMARY = "已依據查得的資料整理以下結果。";
    private static final String PARTIAL_SUMMARY = "已查得部分指定資料，未找到的項目列於下方。";
    private static final String DOCUMENT_GAP_SUMMARY = "文件檢索未提供足夠證據，未據此作肯定結論。";
    private static final String BUSINESS_GAP_SUMMARY = "部分業務查詢未取得足夠證據；已查得內容列於下方。";
    private static final String CLARIFICATION_SUMMARY = "名稱對應多筆主檔，請確認下方候選資料。";
    private final ObjectMapper objectMapper;

    public AiResponseGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** The model chooses harmless connective prose; business claims and sources stay server-owned. */
    public VerifiedAnswer compose(String modelPayload, List<AiToolResult> executions) {
        if (executions.isEmpty() || executions.stream().anyMatch(result -> result == null
                || result.tool() == null || result.status() == null || result.sources() == null
                || (result.status() == AiQueryStatus.ANSWERED
                    && (result.trustedFacts() == null || result.trustedFacts().isBlank())))) {
            throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE, AiFailureReason.NO_TOOL_EXECUTION);
        }
        List<AiToolResult> answered = executions.stream().filter(result -> result.status() == AiQueryStatus.ANSWERED).toList();
        String facts = executions.stream().map(this::statusFacts).filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (answered.isEmpty()) return new VerifiedAnswer(facts.isBlank() ? null : facts, null, "BACKEND_FACTS", null);
        try {
            JsonNode root = objectMapper.readTree(modelPayload);
            String safeSummary = expectedSummary(executions);
            if (root == null || root.size() != 2 || !root.has("summary") || !root.has("factIndexes")
                    || !safeSummary.equals(root.path("summary").asText())
                    || !root.path("factIndexes").isArray()
                    || root.path("factIndexes").size() != answered.size()) {
                return new VerifiedAnswer(facts, null, "BACKEND_FACTS", AiFailureReason.INVALID_FINAL_RESPONSE_STRUCTURE);
            }
            for (int index = 0; index < answered.size(); index++) {
                JsonNode reference = root.path("factIndexes").get(index);
                if (!reference.isIntegralNumber() || reference.asInt() != index) {
                    return new VerifiedAnswer(facts, null, "BACKEND_FACTS", AiFailureReason.INVALID_FINAL_RESPONSE_STRUCTURE);
                }
            }
            return new VerifiedAnswer(facts, safeSummary, "MODEL_EXPLANATION", null);
        } catch (Exception invalidModelText) {
            return new VerifiedAnswer(facts, null, "BACKEND_FACTS", AiFailureReason.INVALID_FINAL_RESPONSE_STRUCTURE);
        }
    }

    private String statusFacts(AiToolResult result) {
        String trusted = result.trustedFacts();
        if (trusted != null && !trusted.isBlank()) return trusted;
        if (result.status() == AiQueryStatus.INSUFFICIENT_EVIDENCE) {
            return result.tool() == com.supplypilot.ai.dto.AiToolType.SEARCH_DOCUMENTS
                    ? "文件檢索證據不足，未據此作出結論。"
                    : "此項業務查詢未取得足夠證據，未據此作出結論。";
        }
        if (result.status() == AiQueryStatus.NEEDS_CLARIFICATION) return "需要補充或確認識別資訊，才能完成此項查詢。";
        if (result.status() == AiQueryStatus.NOT_FOUND) return "查詢已完成，但沒有找到符合條件的資料。";
        return null;
    }

    private String expectedSummary(List<AiToolResult> executions) {
        boolean partial = executions.stream().anyMatch(result -> result.status() == AiQueryStatus.NOT_FOUND)
                && executions.stream().anyMatch(result -> result.status() == AiQueryStatus.ANSWERED)
                || executions.stream().anyMatch(result -> result.status() == AiQueryStatus.ANSWERED
                        && !result.details().unmatchedIdentifiers().isEmpty());
        if (partial) return PARTIAL_SUMMARY;
        if (executions.stream().anyMatch(result -> result.status() == AiQueryStatus.NEEDS_CLARIFICATION)) {
            return CLARIFICATION_SUMMARY;
        }
        if (executions.stream().anyMatch(result -> result.status() == AiQueryStatus.INSUFFICIENT_EVIDENCE
                && result.tool() == com.supplypilot.ai.dto.AiToolType.SEARCH_DOCUMENTS)) {
            return DOCUMENT_GAP_SUMMARY;
        }
        if (executions.stream().anyMatch(result -> result.status() == AiQueryStatus.INSUFFICIENT_EVIDENCE)) {
            return BUSINESS_GAP_SUMMARY;
        }
        return ANSWERED_SUMMARY;
    }

    public record VerifiedAnswer(String answer, String summary, String answerSource, AiFailureReason diagnosticReason) {
    }
}

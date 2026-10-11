package com.supplypilot.ai.service;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class AiResponseGuard {
    private static final Set<String> SAFE_INTROS = Set.of(
            "根據已查得的資料，整理如下。",
            "以下是依據查得資料整理的結果。",
            "查詢結果整理如下。",
            "我將查得的資料分項列出。"
    );
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
            throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE);
        }
        List<AiToolResult> answered = executions.stream()
                .filter(result -> result.status() == AiQueryStatus.ANSWERED).toList();
        if (answered.isEmpty()) return new VerifiedAnswer(null, null);

        String facts = answered.stream().map(AiToolResult::trustedFacts)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (answered.size() != executions.size()) {
            facts += "\n文件證據不足，未據此作出結論。";
        }
        try {
            JsonNode root = objectMapper.readTree(modelPayload);
            if (root == null || root.size() != 2 || !root.has("intro") || !root.has("factIndexes")
                    || !SAFE_INTROS.contains(root.path("intro").asText())
                    || !root.path("factIndexes").isArray()
                    || root.path("factIndexes").size() != answered.size()) {
                return new VerifiedAnswer(facts, "BACKEND_FACTS");
            }
            for (int index = 0; index < answered.size(); index++) {
                JsonNode reference = root.path("factIndexes").get(index);
                if (!reference.isIntegralNumber() || reference.asInt() != index) {
                    return new VerifiedAnswer(facts, "BACKEND_FACTS");
                }
            }
            return new VerifiedAnswer(root.path("intro").asText() + "\n" + facts, "MODEL_EXPLANATION");
        } catch (Exception invalidModelText) {
            return new VerifiedAnswer(facts, "BACKEND_FACTS");
        }
    }

    public record VerifiedAnswer(String answer, String answerSource) {
    }
}

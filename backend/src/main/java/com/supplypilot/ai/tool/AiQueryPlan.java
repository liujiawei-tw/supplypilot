package com.supplypilot.ai.tool;

import java.util.List;

import com.supplypilot.ai.dto.AiToolType;

public record AiQueryPlan(
        AiToolType tool,
        Long recommendationId,
        String materialCode,
        String question,
        List<String> materialCodes,
        String materialName,
        String supplierCode,
        String supplierName,
        String purchaseOrderNumber
) {
    public AiQueryPlan(AiToolType tool, Long recommendationId, String materialCode, String question) {
        this(tool, recommendationId, materialCode, question,
                materialCode == null ? List.of() : List.of(materialCode), null, null, null, null);
    }

    public AiQueryPlan {
        materialCodes = materialCodes == null ? List.of() : List.copyOf(materialCodes);
    }
}

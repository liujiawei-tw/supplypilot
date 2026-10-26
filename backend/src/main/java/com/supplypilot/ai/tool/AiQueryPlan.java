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
        String purchaseOrderNumber,
        List<String> supplierCodes
) {
    public AiQueryPlan(AiToolType tool, Long recommendationId, String materialCode, String question) {
        this(tool, recommendationId, materialCode, question,
                materialCode == null ? List.of() : List.of(materialCode), null, null, null, null, List.of());
    }

    public AiQueryPlan(AiToolType tool, Long recommendationId, String materialCode, String question,
                       List<String> materialCodes, String materialName, String supplierCode,
                       String supplierName, String purchaseOrderNumber) {
        this(tool, recommendationId, materialCode, question, materialCodes, materialName,
                supplierCode, supplierName, purchaseOrderNumber,
                supplierCode == null ? List.of() : List.of(supplierCode));
    }

    public AiQueryPlan {
        materialCodes = materialCodes == null ? List.of() : List.copyOf(materialCodes);
        supplierCodes = supplierCodes == null ? (supplierCode == null ? List.of() : List.of(supplierCode))
                : supplierCodes.stream().filter(code -> code != null && !code.isBlank()).distinct().toList();
    }
}

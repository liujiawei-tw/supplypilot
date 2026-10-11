package com.supplypilot.ai.tool;

import java.util.List;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.inventory.dto.response.InventoryResponse;
import com.supplypilot.inventory.service.InventoryQueryService;
import org.springframework.stereotype.Component;

@Component
public class InventoryTool {
    private final InventoryQueryService inventories;

    public InventoryTool(InventoryQueryService inventories) {
        this.inventories = inventories;
    }

    public AiToolResult execute(AiQueryPlan plan) {
        if (plan.materialCode() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        InventoryResponse inventory = inventories.getInventoryByMaterialCode(plan.materialCode());
        String facts = "%s（%s）的目前庫存為 %s %s，庫存資料更新時間為 %s。".formatted(
                inventory.materialName(), inventory.materialCode(), inventory.quantity(), inventory.unit(), inventory.updatedAt());
        return new AiToolResult(AiToolType.QUERY_INVENTORY, AiQueryStatus.ANSWERED, facts,
                List.of(AiSourceResponse.resource("INVENTORY", inventory.materialId(), inventory.materialCode())));
    }
}

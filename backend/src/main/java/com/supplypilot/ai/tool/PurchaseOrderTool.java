package com.supplypilot.ai.tool;

import java.util.ArrayList;
import java.util.List;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import com.supplypilot.procurement.service.PurchaseOrderService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
public class PurchaseOrderTool {
    private final PurchaseOrderService purchaseOrders;

    public PurchaseOrderTool(PurchaseOrderService purchaseOrders) {
        this.purchaseOrders = purchaseOrders;
    }

    public AiToolResult execute(AiQueryPlan plan) {
        var page = purchaseOrders.listOpen(plan.materialCode(),
                PageRequest.of(0, 100, Sort.by(Sort.Direction.ASC, "id")));
        List<PurchaseOrderResponse> openOrders = page.content();
        List<AiSourceResponse> sources = new ArrayList<>();
        StringBuilder facts = new StringBuilder("Open PO 依既有定義只包含 ORDERED 狀態；總計 ")
                .append(page.totalElements()).append(" 筆，本次回傳 ").append(openOrders.size()).append(" 筆：");
        for (PurchaseOrderResponse order : openOrders) {
            sources.add(AiSourceResponse.resource("PURCHASE_ORDER", order.id(), order.poNumber()));
            facts.append(" %s（ID %s、supplier=%s、status=%s、expectedDeliveryDate=%s、currency=TWD、items=%s）；"
                    .formatted(order.poNumber(), order.id(), order.supplierName(), order.status(),
                            order.expectedDeliveryDate(), order.items()));
        }
        return new AiToolResult(AiToolType.QUERY_OPEN_PO, AiQueryStatus.ANSWERED,
                facts.toString(), List.copyOf(sources));
    }
}

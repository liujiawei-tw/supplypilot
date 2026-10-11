package com.supplypilot.ai.tool;

import java.util.ArrayList;
import java.util.List;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.recommendation.dto.response.RecommendationResponse;
import com.supplypilot.recommendation.dto.response.SupplierRecommendationResultResponse;
import com.supplypilot.recommendation.service.PurchaseRecommendationService;
import org.springframework.stereotype.Component;

@Component
public class RecommendationTool {
    private final PurchaseRecommendationService recommendations;

    public RecommendationTool(PurchaseRecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    public AiToolResult execute(AiQueryPlan plan) {
        RecommendationResponse recommendation = find(plan);
        List<AiSourceResponse> sources = new ArrayList<>();
        sources.add(AiSourceResponse.resource("PURCHASE_RECOMMENDATION", recommendation.id(),
                recommendation.material().code()));

        String facts = recommendationFacts(recommendation);
        if (plan.tool() == AiToolType.QUERY_SUPPLIER_RANKING) {
            List<SupplierRecommendationResultResponse> ranking = recommendations.getSupplierResults(recommendation.id());
            facts = supplierFacts(recommendation, ranking);
            ranking.forEach(candidate -> sources.add(AiSourceResponse.resource(
                    "SUPPLIER_RECOMMENDATION_RESULT",
                    recommendation.id() + ":" + candidate.supplierId(), candidate.supplierCode())));
        }
        return new AiToolResult(plan.tool(), AiQueryStatus.ANSWERED, facts, List.copyOf(sources));
    }

    private RecommendationResponse find(AiQueryPlan plan) {
        if (plan.recommendationId() != null) {
            return recommendations.getRecommendation(plan.recommendationId());
        }
        if (plan.materialCode() != null) {
            return recommendations.getLatestRecommendationByMaterialCode(plan.materialCode());
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private String recommendationFacts(RecommendationResponse response) {
        var calculation = response.calculation();
        var selected = response.recommendation();
        String supplier = selected.supplierId() == null
                ? "沒有建議供應商"
                : "建議供應商為 %s（ID %s，分數 %s）".formatted(
                        selected.supplierName(), selected.supplierId(), selected.score());
        return "建議 #%s（%s）的已保存快照：預期需求 %s %s、安全庫存 %s %s、目前庫存 %s %s、"
                .formatted(response.id(), response.material().code(), calculation.expectedDemand(), response.material().unit(),
                        calculation.safetyStock(), response.material().unit(), calculation.currentInventory(), response.material().unit())
                + "在途庫存 %s %s、淨需求 %s %s；結果為 %s，%s，建議數量 %s %s。"
                .formatted(calculation.incomingInventory(), response.material().unit(), calculation.netRequirement(),
                        response.material().unit(), response.outcome(), supplier, selected.quantity(), response.material().unit());
    }

    private String supplierFacts(
            RecommendationResponse recommendation,
            List<SupplierRecommendationResultResponse> ranking
    ) {
        if (ranking.isEmpty()) {
            return "建議 #%s（%s）沒有已保存的供應商候選結果。"
                    .formatted(recommendation.id(), recommendation.material().code());
        }
        StringBuilder answer = new StringBuilder("建議 #%s（%s）的已保存供應商排名："
                .formatted(recommendation.id(), recommendation.material().code()));
        for (SupplierRecommendationResultResponse candidate : ranking) {
            answer.append(" %s（ID %s）qualified=%s、disqualifiedReason=%s、quantity=%s、unitPrice=%s TWD、totalCost=%s TWD、"
                    .formatted(candidate.supplierName(), candidate.supplierId(), candidate.qualified(),
                            candidate.disqualifiedReason(), candidate.recommendedQuantity(), candidate.unitPrice(), candidate.totalCost()));
            answer.append("leadTimeDays=%s、reliability=%s、quality=%s、score=%s、rank=%s；"
                    .formatted(candidate.leadTimeDays(), candidate.reliabilityScoreSnapshot(),
                            candidate.qualityScoreSnapshot(), candidate.totalScore(), candidate.rank()));
        }
        return answer.toString();
    }
}

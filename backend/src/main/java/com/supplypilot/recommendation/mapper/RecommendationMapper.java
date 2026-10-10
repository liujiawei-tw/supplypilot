package com.supplypilot.recommendation.mapper;

import com.supplypilot.recommendation.domain.PurchaseRecommendation;
import com.supplypilot.recommendation.domain.RecommendationOutcome;
import com.supplypilot.recommendation.domain.SupplierRecommendationResult;
import com.supplypilot.recommendation.dto.response.RecommendationResponse;
import com.supplypilot.recommendation.dto.response.SupplierRecommendationResultResponse;
import com.supplypilot.supplier.domain.Supplier;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RecommendationMapper {

    public RecommendationResponse toResponse(PurchaseRecommendation recommendation) {
        Supplier selected = recommendation.getRecommendedSupplier();
        String selectedSupplierName = recommendation.getSupplierResults().stream()
                .filter(result -> selected != null && Objects.equals(result.getSupplier().getId(), selected.getId()))
                .map(SupplierRecommendationResult::getSupplierNameSnapshot)
                .findFirst()
                .orElse(null);
        return new RecommendationResponse(
                recommendation.getId(),
                new RecommendationResponse.MaterialSnapshot(
                        recommendation.getMaterial().getId(),
                        recommendation.getMaterialCodeSnapshot(),
                        recommendation.getMaterialNameSnapshot(),
                        recommendation.getMaterial().getBaseUnit().name()),
                new RecommendationResponse.Period(
                        recommendation.getPeriodStart(), recommendation.getPeriodEnd()),
                new RecommendationResponse.Calculation(
                        recommendation.getExpectedDemand(),
                        recommendation.getSafetyStock(),
                        recommendation.getCurrentInventory(),
                        recommendation.getIncomingInventory(),
                        recommendation.getNetRequirement()),
                recommendation.getOutcome(),
                new RecommendationResponse.RecommendedSupplier(
                        recommendation.getOutcome() != RecommendationOutcome.PURCHASE_NOT_REQUIRED,
                        selected == null ? null : selected.getId(),
                        selectedSupplierName,
                        recommendation.getRecommendedQuantity(),
                        recommendation.getRecommendedSupplierScore()),
                recommendation.getCreatedAt());
    }

    public SupplierRecommendationResultResponse toResponse(SupplierRecommendationResult result) {
        Supplier supplier = result.getSupplier();
        return new SupplierRecommendationResultResponse(
                supplier.getId(),
                result.getSupplierCodeSnapshot(),
                result.getSupplierNameSnapshot(),
                result.isQualified(),
                result.getDisqualifiedReason(),
                result.getRecommendedQuantity(),
                result.getUnitPriceSnapshot(),
                result.getTotalCost(),
                result.getLeadTimeDaysSnapshot(),
                result.getReliabilityScoreSnapshot(),
                result.getQualityScoreSnapshot(),
                result.getPriceScore(),
                result.getLeadTimeScore(),
                result.getReliabilityScore(),
                result.getQualityScore(),
                result.getTotalScore(),
                result.getRank());
    }
}

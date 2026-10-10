package com.supplypilot.procurement.dto.request;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public class UpdatePurchaseOrderItemRequest {
    @NotNull private Long materialId;
    @NotNull @Positive private BigDecimal quantity;
    @NotNull @PositiveOrZero private BigDecimal unitPrice;
    private Long recommendationId;
    private Long supplierRecommendationResultId;
    @Size(max = 2000) private String supplierOverrideReason;
    @Size(max = 2000) private String quantityOverrideReason;

    public UpdatePurchaseOrderItemRequest() { }

    @JsonSetter("materialId") public void setMaterialId(Long value) { this.materialId = value; }
    @JsonSetter("quantity") public void setQuantity(BigDecimal value) { this.quantity = value; }
    @JsonSetter("unitPrice") public void setUnitPrice(BigDecimal value) { this.unitPrice = value; }
    @JsonSetter("recommendationId") public void setRecommendationId(Long value) { this.recommendationId = value; }
    @JsonSetter("supplierRecommendationResultId") public void setSupplierRecommendationResultId(Long value) { this.supplierRecommendationResultId = value; }
    @JsonSetter("supplierOverrideReason") public void setSupplierOverrideReason(String value) { this.supplierOverrideReason = value; }
    @JsonSetter("quantityOverrideReason") public void setQuantityOverrideReason(String value) { this.quantityOverrideReason = value; }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Property not allowed in request: " + name); }

    public Long materialId() { return materialId; }
    public BigDecimal quantity() { return quantity; }
    public BigDecimal unitPrice() { return unitPrice; }
    public Long recommendationId() { return recommendationId; }
    public Long supplierRecommendationResultId() { return supplierRecommendationResultId; }
    public String supplierOverrideReason() { return supplierOverrideReason; }
    public String quantityOverrideReason() { return quantityOverrideReason; }
    public boolean hasRecommendationInput() {
        return recommendationId != null || supplierRecommendationResultId != null
                || supplierOverrideReason != null || quantityOverrideReason != null;
    }
}

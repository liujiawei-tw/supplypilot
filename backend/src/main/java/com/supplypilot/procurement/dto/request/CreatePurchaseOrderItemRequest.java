package com.supplypilot.procurement.dto.request;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public class CreatePurchaseOrderItemRequest {
    @NotNull private Long materialId;
    @NotNull @Positive private BigDecimal quantity;
    @NotNull @PositiveOrZero private BigDecimal unitPrice;
    private Long recommendationId;
    private Long supplierRecommendationResultId;
    @Size(max = 2000) private String supplierOverrideReason;
    @Size(max = 2000) private String quantityOverrideReason;

    public CreatePurchaseOrderItemRequest() { }

    @JsonSetter("materialId") public void setMaterialId(Long materialId) { this.materialId = materialId; }
    @JsonSetter("quantity") public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    @JsonSetter("unitPrice") public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    @JsonSetter("recommendationId") public void setRecommendationId(Long recommendationId) { this.recommendationId = recommendationId; }
    @JsonSetter("supplierRecommendationResultId") public void setSupplierRecommendationResultId(Long value) { this.supplierRecommendationResultId = value; }
    @JsonSetter("supplierOverrideReason") public void setSupplierOverrideReason(String value) { this.supplierOverrideReason = value; }
    @JsonSetter("quantityOverrideReason") public void setQuantityOverrideReason(String value) { this.quantityOverrideReason = value; }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Property not allowed in request: " + name); }

    @JsonProperty("materialId") public Long materialId() { return materialId; }
    @JsonProperty("quantity") public BigDecimal quantity() { return quantity; }
    @JsonProperty("unitPrice") public BigDecimal unitPrice() { return unitPrice; }
    @JsonProperty("recommendationId") public Long recommendationId() { return recommendationId; }
    @JsonProperty("supplierRecommendationResultId") public Long supplierRecommendationResultId() { return supplierRecommendationResultId; }
    @JsonProperty("supplierOverrideReason") public String supplierOverrideReason() { return supplierOverrideReason; }
    @JsonProperty("quantityOverrideReason") public String quantityOverrideReason() { return quantityOverrideReason; }
    public boolean hasRecommendationInput() {
        return recommendationId != null || supplierRecommendationResultId != null
                || supplierOverrideReason != null || quantityOverrideReason != null;
    }
}

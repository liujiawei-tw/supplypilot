package com.supplypilot.supplier.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.supplypilot.supplier.domain.Currency;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateSupplierMaterialRequest(
        @NotNull Long supplierId, @NotNull Long materialId, @NotNull @DecimalMin("0.0") BigDecimal unitPrice,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal moq,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal packageMultiple,
        @NotNull @Min(0) Integer leadTimeDays, @NotNull Currency currency) {
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Property not allowed in request: " + name); }
}

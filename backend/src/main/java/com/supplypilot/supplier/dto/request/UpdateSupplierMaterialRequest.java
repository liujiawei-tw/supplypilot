package com.supplypilot.supplier.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record UpdateSupplierMaterialRequest(
        @DecimalMin("0.0") BigDecimal unitPrice,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal moq,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal packageMultiple,
        @Min(0) Integer leadTimeDays, SupplierMaterialStatus status) {
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Property not allowed in request: " + name); }
}

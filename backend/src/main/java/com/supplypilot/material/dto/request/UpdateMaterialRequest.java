package com.supplypilot.material.dto.request;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.supplypilot.material.domain.MaterialStatus;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public class UpdateMaterialRequest {

    @Pattern(regexp = ".*\\S.*", message = "Name must not be blank.")
    @Size(max = 100, message = "Name must not exceed 100 characters.")
    private String name;

    @PositiveOrZero(message = "Safety stock must be zero or positive.")
    private BigDecimal safetyStock;

    private MaterialStatus status;

    public UpdateMaterialRequest() {
    }

    public UpdateMaterialRequest(String name, BigDecimal safetyStock, MaterialStatus status) {
        this.name = name;
        this.safetyStock = safetyStock;
        this.status = status;
    }

    @JsonSetter("name") public void setName(String name) { this.name = name; }
    @JsonSetter("safetyStock") public void setSafetyStock(BigDecimal safetyStock) { this.safetyStock = safetyStock; }
    @JsonSetter("status") public void setStatus(MaterialStatus status) { this.status = status; }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }

    public String name() { return name; }
    public BigDecimal safetyStock() { return safetyStock; }
    public MaterialStatus status() { return status; }
}

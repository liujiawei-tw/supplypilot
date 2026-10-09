package com.supplypilot.inventory.dto.request;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class InventoryAdjustmentRequest {

    @NotNull
    @Digits(integer = 15, fraction = 4)
    private BigDecimal quantity;

    @NotBlank
    @Size(max = 500)
    private String reason;

    public InventoryAdjustmentRequest() {
    }

    public InventoryAdjustmentRequest(BigDecimal quantity, String reason) {
        this.quantity = quantity;
        this.reason = reason;
    }

    @JsonSetter("quantity")
    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    @JsonSetter("reason")
    public void setReason(String reason) {
        this.reason = reason;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }

    public BigDecimal quantity() { return quantity; }
    public String reason() { return reason; }

    @AssertTrue(message = "Quantity must not be zero")
    public boolean isQuantityNonZero() {
        return quantity == null || quantity.signum() != 0;
    }
}

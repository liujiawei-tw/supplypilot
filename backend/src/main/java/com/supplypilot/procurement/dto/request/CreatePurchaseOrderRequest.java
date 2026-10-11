package com.supplypilot.procurement.dto.request;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public class CreatePurchaseOrderRequest {
    @NotNull private Long supplierId;
    private LocalDate expectedDeliveryDate;
    @NotEmpty private List<@Valid CreatePurchaseOrderItemRequest> items;

    public CreatePurchaseOrderRequest() { }

    @JsonSetter("supplierId") public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    @JsonSetter("expectedDeliveryDate") public void setExpectedDeliveryDate(LocalDate expectedDeliveryDate) { this.expectedDeliveryDate = expectedDeliveryDate; }
    @JsonSetter("items") public void setItems(List<CreatePurchaseOrderItemRequest> items) { this.items = items; }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Property not allowed in request: " + name); }

    @JsonProperty("supplierId") public Long supplierId() { return supplierId; }
    @JsonProperty("expectedDeliveryDate") public LocalDate expectedDeliveryDate() { return expectedDeliveryDate; }
    @JsonProperty("items") public List<CreatePurchaseOrderItemRequest> items() { return items; }
}

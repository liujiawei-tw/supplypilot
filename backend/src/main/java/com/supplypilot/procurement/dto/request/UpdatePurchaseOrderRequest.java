package com.supplypilot.procurement.dto.request;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public class UpdatePurchaseOrderRequest {
    private Long supplierId;
    private LocalDate expectedDeliveryDate;
    private List<@NotNull @Valid UpdatePurchaseOrderItemRequest> items;
    private boolean supplierIdProvided;
    private boolean expectedDeliveryDateProvided;
    private boolean itemsProvided;

    public UpdatePurchaseOrderRequest() { }

    @JsonSetter("supplierId")
    public void setSupplierId(Long value) {
        if (value == null) throw new IllegalArgumentException("supplierId must not be null");
        supplierIdProvided = true;
        supplierId = value;
    }

    @JsonSetter("expectedDeliveryDate")
    public void setExpectedDeliveryDate(LocalDate value) {
        expectedDeliveryDateProvided = true;
        expectedDeliveryDate = value;
    }

    @JsonSetter("items")
    public void setItems(List<UpdatePurchaseOrderItemRequest> value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("items must not be empty");
        itemsProvided = true;
        items = value;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }

    public Long supplierId() { return supplierId; }
    public LocalDate expectedDeliveryDate() { return expectedDeliveryDate; }
    public List<UpdatePurchaseOrderItemRequest> items() { return items; }
    public boolean supplierIdProvided() { return supplierIdProvided; }
    public boolean expectedDeliveryDateProvided() { return expectedDeliveryDateProvided; }
    public boolean itemsProvided() { return itemsProvided; }
}

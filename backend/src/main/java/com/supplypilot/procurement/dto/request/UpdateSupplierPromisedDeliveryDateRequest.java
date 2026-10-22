package com.supplypilot.procurement.dto.request;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.AssertTrue;

public final class UpdateSupplierPromisedDeliveryDateRequest {
    private LocalDate supplierPromisedDeliveryDate;
    private boolean supplierPromisedDeliveryDateProvided;

    public UpdateSupplierPromisedDeliveryDateRequest() {
    }

    @JsonSetter("supplierPromisedDeliveryDate")
    public void setSupplierPromisedDeliveryDate(LocalDate supplierPromisedDeliveryDate) {
        this.supplierPromisedDeliveryDateProvided = true;
        this.supplierPromisedDeliveryDate = supplierPromisedDeliveryDate;
    }

    public LocalDate supplierPromisedDeliveryDate() {
        return supplierPromisedDeliveryDate;
    }

    @JsonIgnore
    @AssertTrue(message = "supplierPromisedDeliveryDate must be provided")
    public boolean isSupplierPromisedDeliveryDateProvided() {
        return supplierPromisedDeliveryDateProvided;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }
}

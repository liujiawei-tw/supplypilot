package com.supplypilot.procurement.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;

public class RejectPurchaseOrderRequest {
    private String reason;

    public RejectPurchaseOrderRequest() { }

    @JsonSetter("reason")
    public void setReason(String reason) {
        this.reason = reason;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }

    public String reason() {
        return reason;
    }
}

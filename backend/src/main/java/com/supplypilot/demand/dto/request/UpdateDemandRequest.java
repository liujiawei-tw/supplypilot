package com.supplypilot.demand.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class UpdateDemandRequest {
    @Positive private BigDecimal quantity;
    private LocalDate requiredDate;
    @Size(max = 500) private String note;
    private boolean noteProvided;

    @JsonSetter("quantity") public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    @JsonSetter("requiredDate") public void setRequiredDate(LocalDate requiredDate) { this.requiredDate = requiredDate; }
    @JsonSetter("note") public void setNote(String note) { this.note = note; this.noteProvided = true; }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Property not allowed in request: " + name); }
    public BigDecimal quantity() { return quantity; }
    public LocalDate requiredDate() { return requiredDate; }
    public String note() { return note; }
    public boolean noteProvided() { return noteProvided; }
}

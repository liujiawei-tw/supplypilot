package com.supplypilot.demand.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class CreateDemandRequest {
    @NotNull private Long materialId;
    @NotNull @Positive private BigDecimal quantity;
    @NotNull private LocalDate requiredDate;
    @Size(max = 500) private String note;

    public CreateDemandRequest() { }
    public CreateDemandRequest(Long materialId, BigDecimal quantity, LocalDate requiredDate, String note) {
        this.materialId = materialId; this.quantity = quantity; this.requiredDate = requiredDate; this.note = note;
    }
    @JsonSetter("materialId") public void setMaterialId(Long materialId) { this.materialId = materialId; }
    @JsonSetter("quantity") public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    @JsonSetter("requiredDate") public void setRequiredDate(LocalDate requiredDate) { this.requiredDate = requiredDate; }
    @JsonSetter("note") public void setNote(String note) { this.note = note; }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Property not allowed in request: " + name); }
    public Long materialId() { return materialId; }
    public BigDecimal quantity() { return quantity; }
    public LocalDate requiredDate() { return requiredDate; }
    public String note() { return note; }
}

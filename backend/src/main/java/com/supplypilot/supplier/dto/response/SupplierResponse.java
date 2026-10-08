package com.supplypilot.supplier.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import com.supplypilot.supplier.domain.SupplierStatus;

public record SupplierResponse(Long id, String code, String name, String country, BigDecimal reliabilityScore,
                               BigDecimal qualityScore, SupplierStatus status, Instant createdAt, Instant updatedAt) { }

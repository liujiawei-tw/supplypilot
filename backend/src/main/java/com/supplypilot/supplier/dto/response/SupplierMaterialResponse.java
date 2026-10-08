package com.supplypilot.supplier.dto.response;

import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record SupplierMaterialResponse(Long id, Long supplierId, Long materialId, BigDecimal unitPrice, BigDecimal moq,
                                       BigDecimal packageMultiple, Integer leadTimeDays, Currency currency,
                                       SupplierMaterialStatus status, Instant createdAt, Instant updatedAt) { }

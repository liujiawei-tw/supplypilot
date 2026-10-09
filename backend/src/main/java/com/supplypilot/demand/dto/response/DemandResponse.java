package com.supplypilot.demand.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import com.supplypilot.demand.domain.DemandStatus;

public record DemandResponse(Long id, Long materialId, BigDecimal quantity, LocalDate requiredDate,
                             DemandStatus status, String note, Long createdBy, Instant createdAt, Instant updatedAt) { }

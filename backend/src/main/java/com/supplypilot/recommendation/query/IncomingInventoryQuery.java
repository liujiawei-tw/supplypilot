package com.supplypilot.recommendation.query;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface IncomingInventoryQuery {

    BigDecimal calculateIncomingQuantity(Long materialId, LocalDate requiredDate);
}

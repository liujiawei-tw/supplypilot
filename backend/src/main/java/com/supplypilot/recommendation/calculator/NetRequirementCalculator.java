package com.supplypilot.recommendation.calculator;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import java.math.BigDecimal;

public class NetRequirementCalculator {

    public BigDecimal calculate(
            BigDecimal expectedDemand,
            BigDecimal safetyStock,
            BigDecimal currentInventory,
            BigDecimal incomingInventory) {
        BigDecimal rawNetRequirement = requireNonNegative(expectedDemand)
                .add(requireNonNegative(safetyStock))
                .subtract(requireNonNegative(currentInventory))
                .subtract(requireNonNegative(incomingInventory));

        return rawNetRequirement.max(BigDecimal.ZERO);
    }

    private static BigDecimal requireNonNegative(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }
}

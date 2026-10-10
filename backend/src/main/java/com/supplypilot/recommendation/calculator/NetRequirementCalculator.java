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

    public ShortageStatus determineShortageStatus(BigDecimal netRequirement) {
        return requireNonNegative(netRequirement).compareTo(BigDecimal.ZERO) > 0
                ? ShortageStatus.SHORTAGE
                : ShortageStatus.NO_SHORTAGE;
    }

    private static BigDecimal requireNonNegative(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }
}

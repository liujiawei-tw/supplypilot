package com.supplypilot.recommendation.calculator;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import java.math.BigDecimal;

public class PurchaseQuantityCalculator {

    public BigDecimal calculate(
            BigDecimal netRequirement, BigDecimal moq, BigDecimal packageMultiple) {
        BigDecimal baseQuantity = requireNonNegative(netRequirement).max(requirePositive(moq));
        if (packageMultiple == null) {
            return baseQuantity;
        }

        BigDecimal validPackageMultiple = requirePositive(packageMultiple);
        BigDecimal[] quotientAndRemainder = baseQuantity.divideAndRemainder(validPackageMultiple);
        if (quotientAndRemainder[1].compareTo(BigDecimal.ZERO) == 0) {
            return baseQuantity;
        }

        return quotientAndRemainder[0].add(BigDecimal.ONE).multiply(validPackageMultiple);
    }

    public BigDecimal calculateTotalPurchaseCost(BigDecimal recommendedQuantity, BigDecimal unitPrice) {
        return requireNonNegative(recommendedQuantity).multiply(requireNonNegative(unitPrice));
    }

    private static BigDecimal requireNonNegative(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private static BigDecimal requirePositive(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }
}

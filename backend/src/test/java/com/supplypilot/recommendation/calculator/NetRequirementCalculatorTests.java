package com.supplypilot.recommendation.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class NetRequirementCalculatorTests {

    private final NetRequirementCalculator calculator = new NetRequirementCalculator();

    @Test
    void shouldCalculateNetRequirementFromDemandSafetyStockAndInventory() {
        BigDecimal result = calculator.calculate(
                new BigDecimal("100"), new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("10"));

        assertThat(result).isEqualByComparingTo("80");
    }

    @Test
    void shouldReturnZeroWhenRawNetRequirementIsZero() {
        BigDecimal result = calculator.calculate(
                new BigDecimal("100"), new BigDecimal("20"), new BigDecimal("110"), new BigDecimal("10"));

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldReturnZeroWhenInventoryExceedsRequirement() {
        BigDecimal result = calculator.calculate(
                new BigDecimal("100"), new BigDecimal("20"), new BigDecimal("150"), BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldPreserveDecimalPrecisionWhenCalculatingNetRequirement() {
        BigDecimal result = calculator.calculate(
                new BigDecimal("100.75"), new BigDecimal("20.25"), new BigDecimal("30.50"), new BigDecimal("10.10"));

        assertThat(result).isEqualByComparingTo("80.40");
    }

    @Test
    void shouldRejectNullOrNegativeBusinessQuantities() {
        assertThatThrownBy(() -> calculator.calculate(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-1"), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}

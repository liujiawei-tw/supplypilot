package com.supplypilot.recommendation.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PurchaseQuantityCalculatorTests {

    private final PurchaseQuantityCalculator calculator = new PurchaseQuantityCalculator();

    @Test
    void shouldRaisePurchaseQuantityToMoq() {
        BigDecimal result = calculator.calculate(new BigDecimal("80"), new BigDecimal("100"), null);

        assertThat(result).isEqualByComparingTo("100");
    }

    @Test
    void shouldRoundPurchaseQuantityUpToPackageMultiple() {
        BigDecimal result = calculator.calculate(new BigDecimal("105"), new BigDecimal("50"), new BigDecimal("20"));

        assertThat(result).isEqualByComparingTo("120");
    }

    @Test
    void shouldReturnNetRequirementWhenMoqIsLowerAndPackageMultipleIsAbsent() {
        BigDecimal result = calculator.calculate(new BigDecimal("105"), new BigDecimal("50"), null);

        assertThat(result).isEqualByComparingTo("105");
    }

    @Test
    void shouldRoundMoqUpToPackageMultiple() {
        BigDecimal result = calculator.calculate(new BigDecimal("80"), new BigDecimal("105"), new BigDecimal("20"));

        assertThat(result).isEqualByComparingTo("120");
    }

    @Test
    void shouldNotAddAnotherPackageWhenBaseQuantityIsExactMultiple() {
        BigDecimal result = calculator.calculate(new BigDecimal("120"), new BigDecimal("50"), new BigDecimal("20"));

        assertThat(result).isEqualByComparingTo("120");
    }

    @Test
    void shouldReturnMoqWhenNetRequirementEqualsMoq() {
        BigDecimal result = calculator.calculate(new BigDecimal("100"), new BigDecimal("100"), null);

        assertThat(result).isEqualByComparingTo("100");
    }

    @Test
    void shouldRoundDecimalQuantitiesUsingDecimalPackageMultiple() {
        BigDecimal result = calculator.calculate(new BigDecimal("21"), BigDecimal.ONE, new BigDecimal("2.5"));

        assertThat(result).isEqualByComparingTo("22.5");
    }

    @Test
    void shouldCalculateTotalPurchaseCostUsingBigDecimal() {
        BigDecimal result = calculator.calculateTotalPurchaseCost(new BigDecimal("120"), new BigDecimal("80.25"));

        assertThat(result).isEqualByComparingTo("9630.00");
    }

    @Test
    void shouldRejectInvalidCalculationInputs() {
        assertThatThrownBy(() -> calculator.calculate(new BigDecimal("-1"), BigDecimal.ONE, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE, BigDecimal.ZERO, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}

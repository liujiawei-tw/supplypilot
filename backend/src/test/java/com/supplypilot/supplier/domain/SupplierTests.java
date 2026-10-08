package com.supplypilot.supplier.domain;

import java.math.BigDecimal;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.testsupport.TestDataConstants;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierTests {
    @Test
    void createsActiveSupplierAndSupportsLifecycleAndEditableUpdates() {
        Supplier supplier = Supplier.create(" SUP-001 ", " ABC Foods ", " TW ", new BigDecimal("0"), new BigDecimal("100"), TestDataConstants.TEST_INSTANT);
        assertThat(supplier.getCode()).isEqualTo("SUP-001");
        assertThat(supplier.getStatus()).isEqualTo(SupplierStatus.ACTIVE);
        supplier.update("ABC Food Group", "", new BigDecimal("92.5"), new BigDecimal("95"), SupplierStatus.INACTIVE, TestDataConstants.TEST_INSTANT.plusSeconds(1));
        assertThat(supplier.getName()).isEqualTo("ABC Food Group");
        assertThat(supplier.getCountry()).isNull();
        assertThat(supplier.getStatus()).isEqualTo(SupplierStatus.INACTIVE);
        supplier.update(null, null, null, null, SupplierStatus.ACTIVE, TestDataConstants.TEST_INSTANT.plusSeconds(2));
        assertThat(supplier.getStatus()).isEqualTo(SupplierStatus.ACTIVE);
        assertThat(supplier.getCode()).isEqualTo("SUP-001");
    }
    @Test
    void rejectsMissingRequiredFieldsAndScoresOutsideRange() {
        assertThatThrownBy(() -> Supplier.create(" ", "Name", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Supplier.create("SUP-1", " ", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Supplier.create("SUP-1", "Name", null, new BigDecimal("-0.01"), BigDecimal.ZERO, TestDataConstants.TEST_INSTANT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Supplier.create("SUP-1", "Name", null, BigDecimal.ZERO, new BigDecimal("100.01"), TestDataConstants.TEST_INSTANT)).isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptsZeroAndOneHundredOnCreateAndUpdate() {
        Supplier supplier = Supplier.create("SUP-BOUNDARY", "Name", null, BigDecimal.ZERO,
                new BigDecimal("100"), TestDataConstants.TEST_INSTANT);
        supplier.update(null, null, new BigDecimal("100"), BigDecimal.ZERO, null,
                TestDataConstants.TEST_INSTANT.plusSeconds(1));

        assertThat(supplier.getReliabilityScore()).isEqualByComparingTo("100");
        assertThat(supplier.getQualityScore()).isEqualByComparingTo("0");
    }

    @Test
    void rejectsScoresOutsideRangeOnUpdateWithoutChangingExistingState() {
        Supplier supplier = Supplier.create("SUP-SAFE", "Name", null, new BigDecimal("50"),
                new BigDecimal("60"), TestDataConstants.TEST_INSTANT);

        assertThatThrownBy(() -> supplier.update(null, null, new BigDecimal("-0.01"), null, null,
                TestDataConstants.TEST_INSTANT.plusSeconds(1))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> supplier.update(null, null, null, new BigDecimal("100.01"), null,
                TestDataConstants.TEST_INSTANT.plusSeconds(1))).isInstanceOf(BusinessException.class);
        assertThat(supplier.getReliabilityScore()).isEqualByComparingTo("50");
        assertThat(supplier.getQualityScore()).isEqualByComparingTo("60");
    }
}

package com.supplypilot.supplier.domain;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.testsupport.TestDataConstants;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SupplierMaterialTests {
    @Test
    void createsActiveRelationshipAndUpdatesOnlyPermittedState() {
        SupplierMaterial value = SupplierMaterial.create(supplier(), material(), BigDecimal.ZERO, new BigDecimal("1"), null, 0, Currency.TWD, TestDataConstants.TEST_INSTANT);
        assertThat(value.getStatus()).isEqualTo(SupplierMaterialStatus.ACTIVE);
        assertThat(value.getPackageMultiple()).isNull();
        value.update(new BigDecimal("80"), new BigDecimal("100"), new BigDecimal("25"), 14, SupplierMaterialStatus.INACTIVE, TestDataConstants.TEST_INSTANT.plusSeconds(1));
        assertThat(value.getUnitPrice()).isEqualByComparingTo("80");
        assertThat(value.getMoq()).isEqualByComparingTo("100");
        assertThat(value.getPackageMultiple()).isEqualByComparingTo("25");
        assertThat(value.getLeadTimeDays()).isEqualTo(14);
        assertThat(value.getStatus()).isEqualTo(SupplierMaterialStatus.INACTIVE);
        assertThat(value.getCurrency()).isEqualTo(Currency.TWD);
    }
    @Test
    void rejectsInvalidNumericBoundariesAndUnsupportedCurrency() {
        assertThatThrownBy(() -> SupplierMaterial.create(supplier(), material(), new BigDecimal("-0.01"), BigDecimal.ONE, null, 0, Currency.TWD, TestDataConstants.TEST_INSTANT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SupplierMaterial.create(supplier(), material(), BigDecimal.ZERO, BigDecimal.ZERO, null, 0, Currency.TWD, TestDataConstants.TEST_INSTANT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SupplierMaterial.create(supplier(), material(), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, 0, Currency.TWD, TestDataConstants.TEST_INSTANT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SupplierMaterial.create(supplier(), material(), BigDecimal.ZERO, BigDecimal.ONE, null, -1, Currency.TWD, TestDataConstants.TEST_INSTANT)).isInstanceOf(BusinessException.class);
    }
    private Supplier supplier() { return Supplier.create("SUP-SM", "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT); }
    private Material material() { return Material.create("MAT-SM", "Material", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT); }
}

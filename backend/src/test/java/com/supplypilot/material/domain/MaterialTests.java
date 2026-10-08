package com.supplypilot.material.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.testsupport.TestDataConstants;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterialTests {
    private static final Instant NOW = TestDataConstants.TEST_INSTANT;

    @Test
    void createsActiveMaterialAndOnlyUpdatesEditableState() {
        Material material = Material.create(" MAT-001 ", " Strawberry ", BaseUnit.KG, new BigDecimal("10"), NOW);

        assertThat(material.getCode()).isEqualTo("MAT-001");
        assertThat(material.getName()).isEqualTo("Strawberry");
        assertThat(material.getBaseUnit()).isEqualTo(BaseUnit.KG);
        assertThat(material.getStatus()).isEqualTo(MaterialStatus.ACTIVE);

        Instant later = NOW.plusSeconds(60);
        material.update("Updated Strawberry", new BigDecimal("20"), MaterialStatus.INACTIVE, later);
        assertThat(material.getCode()).isEqualTo("MAT-001");
        assertThat(material.getBaseUnit()).isEqualTo(BaseUnit.KG);
        assertThat(material.getName()).isEqualTo("Updated Strawberry");
        assertThat(material.getSafetyStock()).isEqualByComparingTo("20");
        assertThat(material.getStatus()).isEqualTo(MaterialStatus.INACTIVE);
        assertThat(material.getUpdatedAt()).isEqualTo(later);
    }

    @Test
    void rejectsBlankRequiredFieldsAndNegativeSafetyStock() {
        assertThatThrownBy(() -> Material.create(" ", "Name", BaseUnit.KG, BigDecimal.ZERO, NOW))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> Material.create("MAT-1", " ", BaseUnit.KG, BigDecimal.ZERO, NOW))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> Material.create("MAT-1", "Name", BaseUnit.KG, new BigDecimal("-0.01"), NOW))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void rejectsNegativeSafetyStockOnUpdate() {
        Material material = Material.create("MAT-1", "Name", BaseUnit.KG, BigDecimal.ZERO, NOW);
        assertThatThrownBy(() -> material.update(null, new BigDecimal("-1"), null, NOW.plusSeconds(1)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}

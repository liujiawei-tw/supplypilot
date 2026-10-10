package com.supplypilot.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTests {

    private static final Instant INITIAL_TIME = Instant.parse("2026-10-08T00:00:00Z");
    private static final Instant ADJUSTED_TIME = INITIAL_TIME.plusSeconds(60);

    @Test
    void adjustsSignedDeltasAndUpdatesTimestamp() {
        Inventory inventory = inventory();
        inventory.adjust(new BigDecimal("100"), INITIAL_TIME);

        inventory.adjust(new BigDecimal("-30"), ADJUSTED_TIME);
        assertThat(inventory.getQuantity()).isEqualByComparingTo("70.0000");
        assertThat(inventory.getUpdatedAt()).isEqualTo(ADJUSTED_TIME);

        inventory.adjust(new BigDecimal("30"), ADJUSTED_TIME.plusSeconds(60));
        assertThat(inventory.getQuantity()).isEqualByComparingTo("100.0000");
    }

    @Test
    void rejectsZeroAndNegativeResultWithoutChangingState() {
        Inventory inventory = inventory();
        inventory.adjust(new BigDecimal("100"), INITIAL_TIME);

        assertThatThrownBy(() -> inventory.adjust(BigDecimal.ZERO, ADJUSTED_TIME))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThatThrownBy(() -> inventory.adjust(new BigDecimal("-101"), ADJUSTED_TIME))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.NEGATIVE_QUANTITY);

        assertThat(inventory.getQuantity()).isEqualByComparingTo("100.0000");
        assertThat(inventory.getUpdatedAt()).isEqualTo(INITIAL_TIME);
    }

    @Test
    void rejectsNullAndDatabaseIncompatibleDecimalValues() {
        Inventory inventory = inventory();

        assertThatThrownBy(() -> inventory.adjust(null, ADJUSTED_TIME))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThatThrownBy(() -> inventory.adjust(new BigDecimal("1.00001"), ADJUSTED_TIME))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThatThrownBy(() -> inventory.adjust(new BigDecimal("1000000000000000"), ADJUSTED_TIME))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
    }

    @Test
    void replacesSnapshotWithoutChangingAdjustSemantics() {
        Inventory inventory = inventory();
        inventory.adjust(new BigDecimal("100"), INITIAL_TIME);
        inventory.replaceSnapshot(new BigDecimal("200"), ADJUSTED_TIME);
        assertThat(inventory.getQuantity()).isEqualByComparingTo("200.0000");
        inventory.replaceSnapshot(BigDecimal.ZERO, ADJUSTED_TIME.plusSeconds(60));
        assertThat(inventory.getQuantity()).isEqualByComparingTo("0.0000");
        inventory.replaceSnapshot(new BigDecimal("50"), ADJUSTED_TIME.plusSeconds(120));
        assertThat(inventory.getQuantity()).isEqualByComparingTo("50.0000");
        assertThatThrownBy(() -> inventory.replaceSnapshot(null, ADJUSTED_TIME))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThatThrownBy(() -> inventory.replaceSnapshot(BigDecimal.ONE.negate(), ADJUSTED_TIME))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.NEGATIVE_QUANTITY);
    }

    private Inventory inventory() {
        Material material = Material.create("MAT-INV", "Inventory material", BaseUnit.KG, BigDecimal.ZERO, INITIAL_TIME);
        return Inventory.create(material, INITIAL_TIME);
    }
}

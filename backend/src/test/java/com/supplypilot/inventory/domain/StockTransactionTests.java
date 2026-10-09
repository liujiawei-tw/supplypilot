package com.supplypilot.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockTransactionTests {

    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void createsManualAdjustmentAuditRecordWithSignedDeltaAndActor() {
        Material material = Material.create("MAT-001", "Material", BaseUnit.KG, BigDecimal.ZERO, NOW);
        User actor = User.create("warehouse", "hash", "Warehouse", UserRole.WAREHOUSE_STAFF, NOW);

        StockTransaction transaction = StockTransaction.manualAdjustment(
                material, new BigDecimal("-30"), " Damaged inventory ", actor, NOW);

        assertThat(transaction.getType()).isEqualTo(StockTransactionType.MANUAL_ADJUSTMENT);
        assertThat(transaction.getQuantity()).isEqualByComparingTo("-30.0000");
        assertThat(transaction.getPurchaseOrderId()).isNull();
        assertThat(transaction.getReason()).isEqualTo("Damaged inventory");
        assertThat(transaction.getCreatedBy()).isSameAs(actor);
        assertThat(transaction.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsInvalidManualAdjustmentReasonOrDelta() {
        Material material = Material.create("MAT-001", "Material", BaseUnit.KG, BigDecimal.ZERO, NOW);
        User actor = User.create("warehouse", "hash", "Warehouse", UserRole.WAREHOUSE_STAFF, NOW);

        assertThatThrownBy(() -> StockTransaction.manualAdjustment(material, BigDecimal.ZERO, "Reason", actor, NOW))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThatThrownBy(() -> StockTransaction.manualAdjustment(material, new BigDecimal("1.00001"), "Reason", actor, NOW))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThatThrownBy(() -> StockTransaction.manualAdjustment(material, BigDecimal.ONE, "   ", actor, NOW))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}

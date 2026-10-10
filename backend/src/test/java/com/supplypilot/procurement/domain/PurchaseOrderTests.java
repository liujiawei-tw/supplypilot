package com.supplypilot.procurement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import org.junit.jupiter.api.Test;

class PurchaseOrderTests {
    private final Supplier supplier = Supplier.create("SUP-PO", "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT);
    private final User user = User.create("buyer", "hash", "Buyer", UserRole.PROCUREMENT_STAFF, TestDataConstants.TEST_INSTANT);
    private final Material material = Material.create("MAT-PO", "Material", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT);

    @Test void createsDraftAndDerivesItemUnitFromMaterial() {
        PurchaseOrder order = PurchaseOrder.create(supplier, null, user, TestDataConstants.TEST_INSTANT);
        order.assignPendingPoNumber("PENDING-test");
        order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT));

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(order.getItems()).singleElement().extracting(PurchaseOrderItem::getUnit).isEqualTo(BaseUnit.KG);
        assertThat(order.getItems().getFirst().getUnitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test void rejectsDuplicateMaterialAndInvalidQuantityOrPrice() {
        PurchaseOrder order = PurchaseOrder.create(supplier, null, user, TestDataConstants.TEST_INSTANT);
        order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT));

        assertThatThrownBy(() -> order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_MATERIAL);
        assertThatThrownBy(() -> PurchaseOrderItem.create(material, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThatThrownBy(() -> PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.ONE.negate(), TestDataConstants.TEST_INSTANT))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}

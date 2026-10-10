package com.supplypilot.procurement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test void draftEditIsOwnedByAggregateAndPreservesExistingItemIdentityAndCreationTime() {
        PurchaseOrder order = PurchaseOrder.create(supplier, null, user, TestDataConstants.TEST_INSTANT);
        PurchaseOrderItem existing = PurchaseOrderItem.create(
                material, BigDecimal.ONE, BigDecimal.TEN, TestDataConstants.TEST_INSTANT);
        ReflectionTestUtils.setField(material, "id", 1L);
        ReflectionTestUtils.setField(existing, "id", 10L);
        order.addItem(existing);
        PurchaseOrderItem replacement = PurchaseOrderItem.create(
                material, new BigDecimal("3"), new BigDecimal("12"), TestDataConstants.TEST_INSTANT.plusSeconds(30));

        order.editDraft(supplier, false, LocalDate.of(2026, 10, 20), true,
                List.of(replacement), true, TestDataConstants.TEST_INSTANT.plusSeconds(60));

        assertThat(order.getExpectedDeliveryDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(order.getItems()).singleElement().isSameAs(existing);
        assertThat(existing.getId()).isEqualTo(10L);
        assertThat(existing.getCreatedAt()).isEqualTo(TestDataConstants.TEST_INSTANT);
        assertThat(existing.getQuantity()).isEqualByComparingTo("3");
        assertThat(existing.getUnitPrice()).isEqualByComparingTo("12");
        assertThat(order.getUpdatedAt()).isEqualTo(TestDataConstants.TEST_INSTANT.plusSeconds(60));
    }

    @Test void nonDraftCannotChangeSupplierDateOrItems() {
        PurchaseOrder order = PurchaseOrder.create(supplier, null, user, TestDataConstants.TEST_INSTANT);
        ReflectionTestUtils.setField(order, "status", PurchaseOrderStatus.APPROVED);

        assertThatThrownBy(() -> order.editDraft(supplier, true, LocalDate.now(), true,
                List.of(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.ZERO,
                        TestDataConstants.TEST_INSTANT)), true, TestDataConstants.TEST_INSTANT.plusSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PO_NOT_EDITABLE);
        assertThat(order.getExpectedDeliveryDate()).isNull();
        assertThat(order.getItems()).isEmpty();
    }

    @Test void approvesDraftWithAuthenticatedAuditAndPreservesCreationData() {
        PurchaseOrder order = draftWithItem();
        User manager = User.create("manager", "hash", "Manager", UserRole.PROCUREMENT_MANAGER,
                TestDataConstants.TEST_INSTANT);
        Instant approvedAt = TestDataConstants.TEST_INSTANT.plusSeconds(60);

        order.approve(manager, approvedAt);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
        assertThat(order.getApprovedBy()).isSameAs(manager);
        assertThat(order.getApprovedAt()).isEqualTo(approvedAt);
        assertThat(order.getUpdatedAt()).isEqualTo(approvedAt);
        assertThat(order.getCreatedBy()).isSameAs(user);
        assertThat(order.getCreatedAt()).isEqualTo(TestDataConstants.TEST_INSTANT);
        assertThat(order.getItems()).hasSize(1);
    }

    @Test void rejectsDraftWithTrimmedReasonAndAuthenticatedAudit() {
        PurchaseOrder order = draftWithItem();
        User manager = User.create("rejector", "hash", "Rejector", UserRole.PROCUREMENT_MANAGER,
                TestDataConstants.TEST_INSTANT);
        Instant rejectedAt = TestDataConstants.TEST_INSTANT.plusSeconds(60);

        order.reject("  Quantity exceeds plan.  ", manager, rejectedAt);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.REJECTED);
        assertThat(order.getRejectReason()).isEqualTo("Quantity exceeds plan.");
        assertThat(order.getRejectedBy()).isSameAs(manager);
        assertThat(order.getRejectedAt()).isEqualTo(rejectedAt);
        assertThat(order.getUpdatedAt()).isEqualTo(rejectedAt);
    }

    @Test void rejectsMissingReasonAndApprovalWithoutItems() {
        PurchaseOrder empty = PurchaseOrder.create(supplier, null, user, TestDataConstants.TEST_INSTANT);
        PurchaseOrder order = draftWithItem();

        assertThatThrownBy(() -> empty.approve(user, TestDataConstants.TEST_INSTANT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        for (String reason : new String[] {"", "   "}) {
            assertThatThrownBy(() -> order.reject(reason, user, TestDataConstants.TEST_INSTANT))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.REJECT_REASON_REQUIRED);
        }
        assertThatThrownBy(() -> order.reject(null, user, TestDataConstants.TEST_INSTANT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REJECT_REASON_REQUIRED);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
    }

    @Test void rejectsEveryIllegalLifecycleTransitionIncludingRejectedToOrdered() {
        for (PurchaseOrderStatus status : PurchaseOrderStatus.values()) {
            if (status == PurchaseOrderStatus.DRAFT) continue;
            PurchaseOrder order = draftWithItem();
            ReflectionTestUtils.setField(order, "status", status);

            assertThatThrownBy(() -> order.approve(user, TestDataConstants.TEST_INSTANT))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PO_STATUS_TRANSITION);
            assertThatThrownBy(() -> order.reject("reason", user, TestDataConstants.TEST_INSTANT))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }

        PurchaseOrder rejected = draftWithItem();
        rejected.reject("reason", user, TestDataConstants.TEST_INSTANT);
        assertThatThrownBy(() -> rejected.assertDraftTransitionTo(PurchaseOrderStatus.ORDERED))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PO_STATUS_TRANSITION);
    }

    @Test void marksApprovedOrderAsOrderedWithAuditAndPreservesExistingBusinessData() {
        PurchaseOrder order = PurchaseOrder.create(
                supplier, TestDataConstants.TEST_TODAY.plusDays(2), user, TestDataConstants.TEST_INSTANT);
        order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.TEN,
                TestDataConstants.TEST_INSTANT));
        User manager = User.create("manager-order", "hash", "Manager", UserRole.PROCUREMENT_MANAGER,
                TestDataConstants.TEST_INSTANT);
        order.approve(manager, TestDataConstants.TEST_INSTANT.plusSeconds(30));
        Instant orderedAt = TestDataConstants.TEST_INSTANT.plusSeconds(60);

        order.markOrdered(user, orderedAt);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(order.getOrderedBy()).isSameAs(user);
        assertThat(order.getOrderedAt()).isEqualTo(orderedAt);
        assertThat(order.getUpdatedAt()).isEqualTo(orderedAt);
        assertThat(order.getCreatedBy()).isSameAs(user);
        assertThat(order.getApprovedBy()).isSameAs(manager);
        assertThat(order.getSupplier()).isSameAs(supplier);
        assertThat(order.getItems()).hasSize(1);
    }

    @Test void markOrderedRejectsMissingDeliveryDateAndEveryNonApprovedStatusWithoutChangingAudit() {
        PurchaseOrder missingDate = draftWithItem();
        ReflectionTestUtils.setField(missingDate, "status", PurchaseOrderStatus.APPROVED);
        assertThatThrownBy(() -> missingDate.markOrdered(user, TestDataConstants.TEST_INSTANT.plusSeconds(60)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXPECTED_DELIVERY_DATE_REQUIRED);

        for (PurchaseOrderStatus status : PurchaseOrderStatus.values()) {
            if (status == PurchaseOrderStatus.APPROVED) continue;
            PurchaseOrder order = PurchaseOrder.create(
                    supplier, TestDataConstants.TEST_TODAY.plusDays(1), user, TestDataConstants.TEST_INSTANT);
            order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.TEN,
                    TestDataConstants.TEST_INSTANT));
            ReflectionTestUtils.setField(order, "status", status);
            assertThatThrownBy(() -> order.markOrdered(user, TestDataConstants.TEST_INSTANT.plusSeconds(60)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PO_STATUS_TRANSITION);
            assertThat(order.getOrderedBy()).isNull();
            assertThat(order.getOrderedAt()).isNull();
        }
    }

    @Test void overdueIsDynamicAndOnlyAppliesToOrderedPurchaseOrdersBeforeToday() {
        PurchaseOrder yesterday = approvedOrderWithDate(TestDataConstants.TEST_TODAY.minusDays(1));
        PurchaseOrder today = approvedOrderWithDate(TestDataConstants.TEST_TODAY);
        PurchaseOrder tomorrow = approvedOrderWithDate(TestDataConstants.TEST_TODAY.plusDays(1));
        yesterday.markOrdered(user, TestDataConstants.TEST_INSTANT);
        today.markOrdered(user, TestDataConstants.TEST_INSTANT);
        tomorrow.markOrdered(user, TestDataConstants.TEST_INSTANT);

        assertThat(yesterday.isOverdue(TestDataConstants.TEST_TODAY)).isTrue();
        assertThat(today.isOverdue(TestDataConstants.TEST_TODAY)).isFalse();
        assertThat(tomorrow.isOverdue(TestDataConstants.TEST_TODAY)).isFalse();

        PurchaseOrder approved = approvedOrderWithDate(TestDataConstants.TEST_TODAY.minusDays(1));
        assertThat(approved.isOverdue(TestDataConstants.TEST_TODAY)).isFalse();
        ReflectionTestUtils.setField(yesterday, "status", PurchaseOrderStatus.RECEIVED);
        assertThat(yesterday.isOverdue(TestDataConstants.TEST_TODAY)).isFalse();
    }

    private PurchaseOrder draftWithItem() {
        PurchaseOrder order = PurchaseOrder.create(supplier, null, user, TestDataConstants.TEST_INSTANT);
        order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.TEN,
                TestDataConstants.TEST_INSTANT));
        return order;
    }

    private PurchaseOrder approvedOrderWithDate(LocalDate date) {
        PurchaseOrder order = PurchaseOrder.create(supplier, date, user, TestDataConstants.TEST_INSTANT);
        order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.TEN,
                TestDataConstants.TEST_INSTANT));
        order.approve(user, TestDataConstants.TEST_INSTANT);
        return order;
    }
}

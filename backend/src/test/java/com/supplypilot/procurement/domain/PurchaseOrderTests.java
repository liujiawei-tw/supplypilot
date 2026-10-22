package com.supplypilot.procurement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
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
        order.editDraft(null, false, TestDataConstants.TEST_TODAY.plusDays(1), true,
                null, false, TestDataConstants.TEST_INSTANT);
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

    @Test void missingDeliveryDateRejectsApprovalWithoutChangingDraftAndCanBeSuppliedLater() {
        PurchaseOrder order = draftWithItem();
        Instant originalUpdatedAt = order.getUpdatedAt();
        User manager = User.create("manager-date", "hash", "Manager", UserRole.PROCUREMENT_MANAGER,
                TestDataConstants.TEST_INSTANT);

        assertThat(order.getExpectedDeliveryDate()).isNull();
        assertThatThrownBy(() -> order.approve(manager, TestDataConstants.TEST_INSTANT.plusSeconds(60)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXPECTED_DELIVERY_DATE_REQUIRED);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(order.getApprovedBy()).isNull();
        assertThat(order.getApprovedAt()).isNull();
        assertThat(order.getUpdatedAt()).isEqualTo(originalUpdatedAt);

        order.editDraft(null, false, TestDataConstants.TEST_TODAY.plusDays(1), true,
                null, false, TestDataConstants.TEST_INSTANT.plusSeconds(90));
        order.approve(manager, TestDataConstants.TEST_INSTANT.plusSeconds(120));
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
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
        assertThat(order.getOrderedProgress()).isEqualTo(OrderedProgress.FOLLOW_UP);
        assertThat(order.getOrderedBy()).isSameAs(user);
        assertThat(order.getOrderedAt()).isEqualTo(orderedAt);
        assertThat(order.getUpdatedAt()).isEqualTo(orderedAt);
        assertThat(order.getCreatedBy()).isSameAs(user);
        assertThat(order.getApprovedBy()).isSameAs(manager);
        assertThat(order.getSupplier()).isSameAs(supplier);
        assertThat(order.getItems()).hasSize(1);
    }

    @Test void cancelsApprovedAndFollowUpOrdersWithRequiredReasonAndIndependentActorSnapshot() {
        User admin = User.create("cancel-admin", "hash", "Cancel Admin", UserRole.ADMIN,
                TestDataConstants.TEST_INSTANT);
        PurchaseOrder approved = approvedOrderWithDate(TestDataConstants.TEST_TODAY.plusDays(1));
        approved.cancel("  Supplier changed plan  ", admin, TestDataConstants.TEST_INSTANT.plusSeconds(10));
        assertThat(approved.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(approved.getCancelReason()).isEqualTo("Supplier changed plan");
        assertThat(approved.getCancelledByUserIdSnapshot()).isEqualTo(admin.getId());
        assertThat(approved.getCancelledByUsernameSnapshot()).isEqualTo("cancel-admin");
        assertThat(approved.getCancelledByDisplayNameSnapshot()).isEqualTo("Cancel Admin");
        assertThat(approved.getCancelledAt()).isEqualTo(TestDataConstants.TEST_INSTANT.plusSeconds(10));

        PurchaseOrder ordered = approvedOrderWithDate(TestDataConstants.TEST_TODAY.plusDays(1));
        ordered.markOrdered(user, TestDataConstants.TEST_INSTANT.plusSeconds(1));
        ordered.cancel("No longer required", admin, TestDataConstants.TEST_INSTANT.plusSeconds(20));
        assertThat(ordered.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(ordered.getOrderedProgress()).isNull();
    }

    @Test void receivingInspectionAndLegacyUnknownOrdersCannotBeCancelledOrRepeatedlyMoved() {
        User admin = User.create("cancel-admin-2", "hash", "Cancel Admin", UserRole.ADMIN,
                TestDataConstants.TEST_INSTANT);
        PurchaseOrder order = approvedOrderWithDate(TestDataConstants.TEST_TODAY.plusDays(1));
        order.markOrdered(user, TestDataConstants.TEST_INSTANT.plusSeconds(1));
        order.beginReceivingInspection(TestDataConstants.TEST_INSTANT.plusSeconds(2));
        assertThat(order.getOrderedProgress()).isEqualTo(OrderedProgress.RECEIVING_INSPECTION);
        assertThatThrownBy(() -> order.cancel("late", admin, TestDataConstants.TEST_INSTANT.plusSeconds(3)))
                .isInstanceOf(BusinessException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        assertThatThrownBy(() -> order.beginReceivingInspection(TestDataConstants.TEST_INSTANT.plusSeconds(3)))
                .isInstanceOf(BusinessException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_PO_STATUS_TRANSITION);

        PurchaseOrder legacy = approvedOrderWithDate(TestDataConstants.TEST_TODAY.plusDays(1));
        ReflectionTestUtils.setField(legacy, "status", PurchaseOrderStatus.ORDERED);
        ReflectionTestUtils.setField(legacy, "orderedProgress", OrderedProgress.LEGACY_UNKNOWN);
        assertThatThrownBy(() -> legacy.cancel("legacy", admin, TestDataConstants.TEST_INSTANT.plusSeconds(4)))
                .isInstanceOf(BusinessException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        legacy.beginReceivingInspection(TestDataConstants.TEST_INSTANT.plusSeconds(5));
        assertThat(legacy.getOrderedProgress()).isEqualTo(OrderedProgress.RECEIVING_INSPECTION);
    }

    @Test void rejectsMissingBlankAndOverlongCancellationReasonsWithoutChangingOrder() {
        User admin = User.create("cancel-admin-3", "hash", "Cancel Admin", UserRole.ADMIN,
                TestDataConstants.TEST_INSTANT);
        for (String reason : new String[] {null, "", "  ", "x".repeat(2001)}) {
            PurchaseOrder order = approvedOrderWithDate(TestDataConstants.TEST_TODAY.plusDays(1));
            assertThatThrownBy(() -> order.cancel(reason, admin, TestDataConstants.TEST_INSTANT.plusSeconds(2)))
                    .isInstanceOf(BusinessException.class);
            assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
            assertThat(order.getCancelledAt()).isNull();
        }
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

    @Test void supplierPromiseChangesIndependentlyAndOverdueUsesTaipeiCalendarDate() {
        LocalDate expectedDate = LocalDate.of(2026, 10, 7);
        PurchaseOrder order = approvedOrderWithDate(expectedDate);
        order.updateSupplierPromisedDeliveryDate(LocalDate.of(2026, 10, 30), TestDataConstants.TEST_INSTANT);

        assertThat(order.getExpectedDeliveryDate()).isEqualTo(expectedDate);
        assertThat(order.getSupplierPromisedDeliveryDate()).isEqualTo(LocalDate.of(2026, 10, 30));

        order.markOrdered(user, TestDataConstants.TEST_INSTANT.plusSeconds(30));
        order.updateSupplierPromisedDeliveryDate(LocalDate.of(2026, 11, 5), TestDataConstants.TEST_INSTANT.plusSeconds(60));
        Clock taipeiClock = Clock.fixed(Instant.parse("2026-10-07T16:00:00Z"), ZoneId.of("Asia/Taipei"));
        assertThat(LocalDate.now(taipeiClock)).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(order.isOverdue(LocalDate.now(taipeiClock))).isTrue();

        order.updateSupplierPromisedDeliveryDate(null, TestDataConstants.TEST_INSTANT.plusSeconds(90));
        assertThat(order.getSupplierPromisedDeliveryDate()).isNull();
        assertThat(order.getExpectedDeliveryDate()).isEqualTo(expectedDate);
        assertThat(order.isOverdue(LocalDate.now(taipeiClock))).isTrue();
    }

    @Test void supplierPromiseMayOnlyChangeAfterApprovalAndBeforeReceipt() {
        PurchaseOrder draft = draftWithItem();
        assertThatThrownBy(() -> draft.updateSupplierPromisedDeliveryDate(
                LocalDate.of(2026, 10, 30), TestDataConstants.TEST_INSTANT.plusSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PO_STATUS_TRANSITION);

        PurchaseOrder rejected = draftWithItem();
        ReflectionTestUtils.setField(rejected, "status", PurchaseOrderStatus.REJECTED);
        assertThatThrownBy(() -> rejected.updateSupplierPromisedDeliveryDate(
                LocalDate.of(2026, 10, 30), TestDataConstants.TEST_INSTANT.plusSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PO_STATUS_TRANSITION);

        PurchaseOrder approved = approvedOrderWithDate(TestDataConstants.TEST_TODAY);
        approved.updateSupplierPromisedDeliveryDate(null, TestDataConstants.TEST_INSTANT.plusSeconds(1));
        assertThat(approved.getSupplierPromisedDeliveryDate()).isNull();
    }

    @Test void receivesOnlyOrderedPurchaseOrderWithBackendAuditAndPreservesPriorData() {
        PurchaseOrder order = approvedOrderWithDate(TestDataConstants.TEST_TODAY.minusDays(1));
        order.markOrdered(user, TestDataConstants.TEST_INSTANT.plusSeconds(30));
        User receiver = User.create("receiver", "hash", "Receiver", UserRole.WAREHOUSE_STAFF,
                TestDataConstants.TEST_INSTANT);
        Instant receivedAt = TestDataConstants.TEST_INSTANT.plusSeconds(60);

        order.beginReceivingInspection(TestDataConstants.TEST_INSTANT.plusSeconds(40));
        order.recordReceivingInspection(true, true, true, null, receiver, TestDataConstants.TEST_INSTANT.plusSeconds(50));

        order.receive(receiver, receivedAt);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(order.getReceivedBy()).isSameAs(receiver);
        assertThat(order.getReceivedAt()).isEqualTo(receivedAt);
        assertThat(order.getUpdatedAt()).isEqualTo(receivedAt);
        assertThat(order.getSupplier()).isSameAs(supplier);
        assertThat(order.getApprovedBy()).isSameAs(user);
        assertThat(order.getOrderedBy()).isSameAs(user);
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.isOverdue(TestDataConstants.TEST_TODAY)).isFalse();
    }

    @Test void receivingRejectsEveryNonOrderedStatusAndDuplicateReceivingWithoutAuditMutation() {
        for (PurchaseOrderStatus status : PurchaseOrderStatus.values()) {
            if (status == PurchaseOrderStatus.ORDERED || status == PurchaseOrderStatus.RECEIVED) continue;
            PurchaseOrder order = draftWithItem();
            ReflectionTestUtils.setField(order, "status", status);
            assertThatThrownBy(() -> order.receive(user, TestDataConstants.TEST_INSTANT.plusSeconds(60)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(status == PurchaseOrderStatus.ORDERED
                            ? ErrorCode.RECEIVING_INSPECTION_INCOMPLETE : ErrorCode.INVALID_PO_STATUS);
            assertThat(order.getReceivedBy()).isNull();
            assertThat(order.getReceivedAt()).isNull();
        }

        PurchaseOrder received = approvedOrderWithDate(TestDataConstants.TEST_TODAY);
        received.markOrdered(user, TestDataConstants.TEST_INSTANT.plusSeconds(30));
        received.beginReceivingInspection(TestDataConstants.TEST_INSTANT.plusSeconds(40));
        received.recordReceivingInspection(true, true, true, null, user, TestDataConstants.TEST_INSTANT.plusSeconds(50));
        received.receive(user, TestDataConstants.TEST_INSTANT.plusSeconds(60));
        assertThatThrownBy(() -> received.receive(user, TestDataConstants.TEST_INSTANT.plusSeconds(90)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_RECEIVING);
        assertThat(received.getReceivedAt()).isEqualTo(TestDataConstants.TEST_INSTANT.plusSeconds(60));
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

package com.supplypilot.procurement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.mapper.PurchaseOrderMapper;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.recommendation.repository.PurchaseRecommendationRepository;
import com.supplypilot.recommendation.repository.SupplierRecommendationResultRepository;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTests {
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private SupplierMaterialRepository supplierMaterialRepository;
    @Mock private PurchaseRecommendationRepository purchaseRecommendationRepository;
    @Mock private SupplierRecommendationResultRepository supplierRecommendationResultRepository;
    @Mock private UserRepository userRepository;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private PurchaseOrderMapper purchaseOrderMapper;

    private PurchaseOrderService service;
    private User staff;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TestDataConstants.TEST_INSTANT, ZoneOffset.UTC);
        service = new PurchaseOrderService(purchaseOrderRepository, supplierRepository, materialRepository,
                supplierMaterialRepository, purchaseRecommendationRepository,
                supplierRecommendationResultRepository, userRepository, currentUserProvider,
                purchaseOrderMapper, clock);
        staff = User.create("orderer", "hash", "Orderer", UserRole.PROCUREMENT_STAFF,
                TestDataConstants.TEST_INSTANT);
        org.springframework.test.util.ReflectionTestUtils.setField(staff, "id", 99L);
    }

    @Test
    void markOrderedUsesAuthenticatedUserAndPersistsAggregate() {
        PurchaseOrder order = approvedOrder();
        when(purchaseOrderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));
        when(currentUserProvider.getCurrentUserId()).thenReturn(staff.getId());
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(purchaseOrderRepository.saveAndFlush(order)).thenReturn(order);

        service.markOrdered(10L);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(order.getOrderedBy()).isSameAs(staff);
        assertThat(order.getOrderedAt()).isEqualTo(TestDataConstants.TEST_INSTANT);
        verify(purchaseOrderRepository).saveAndFlush(order);
        verify(purchaseOrderMapper).toResponse(order);
    }

    @Test
    void markOrderedReturnsNotFoundBeforeResolvingCurrentUser() {
        when(purchaseOrderRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markOrdered(404L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_FOUND);
    }

    @Test
    void approveUsesCurrentUserAndPersistsOnlyWhenDeliveryDateExists() {
        PurchaseOrder order = draftOrder(TestDataConstants.TEST_TODAY.plusDays(2));
        User manager = manager();
        when(purchaseOrderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));
        when(currentUserProvider.getCurrentUserId()).thenReturn(manager.getId());
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(purchaseOrderRepository.saveAndFlush(order)).thenReturn(order);

        service.approve(10L);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
        assertThat(order.getApprovedBy()).isSameAs(manager);
        assertThat(order.getApprovedAt()).isEqualTo(TestDataConstants.TEST_INSTANT);
        verify(purchaseOrderRepository).saveAndFlush(order);
    }

    @Test
    void approveWithoutDeliveryDateDoesNotPersist() {
        PurchaseOrder order = draftOrder(null);
        User manager = manager();
        when(purchaseOrderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));
        when(currentUserProvider.getCurrentUserId()).thenReturn(manager.getId());
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));

        assertThatThrownBy(() -> service.approve(10L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXPECTED_DELIVERY_DATE_REQUIRED);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(order.getApprovedBy()).isNull();
        verify(purchaseOrderRepository, never()).saveAndFlush(order);
    }

    private User manager() {
        User manager = User.create("manager-approve", "hash", "Manager", UserRole.PROCUREMENT_MANAGER,
                TestDataConstants.TEST_INSTANT);
        org.springframework.test.util.ReflectionTestUtils.setField(manager, "id", 100L);
        return manager;
    }

    private PurchaseOrder draftOrder(java.time.LocalDate date) {
        Supplier supplier = Supplier.create("SUP-APPROVE", "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO,
                TestDataConstants.TEST_INSTANT);
        Material material = Material.create("MAT-APPROVE", "Material", BaseUnit.KG, BigDecimal.ZERO,
                TestDataConstants.TEST_INSTANT);
        PurchaseOrder order = PurchaseOrder.create(supplier, date, staff, TestDataConstants.TEST_INSTANT);
        order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.TEN,
                TestDataConstants.TEST_INSTANT));
        return order;
    }

    private PurchaseOrder approvedOrder() {
        Supplier supplier = Supplier.create("SUP-ORDER", "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO,
                TestDataConstants.TEST_INSTANT);
        Material material = Material.create("MAT-ORDER", "Material", BaseUnit.KG, BigDecimal.ZERO,
                TestDataConstants.TEST_INSTANT);
        PurchaseOrder order = PurchaseOrder.create(supplier, TestDataConstants.TEST_TODAY.plusDays(2), staff,
                TestDataConstants.TEST_INSTANT.minusSeconds(120));
        order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.TEN,
                TestDataConstants.TEST_INSTANT.minusSeconds(120)));
        User manager = User.create("manager-order", "hash", "Manager", UserRole.PROCUREMENT_MANAGER,
                TestDataConstants.TEST_INSTANT);
        order.approve(manager, TestDataConstants.TEST_INSTANT.minusSeconds(60));
        return order;
    }
}

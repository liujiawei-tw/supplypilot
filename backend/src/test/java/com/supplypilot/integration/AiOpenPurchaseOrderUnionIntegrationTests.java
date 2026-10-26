package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.domain.PurchaseOrderStage;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.domain.OrderedProgress;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.procurement.service.PurchaseOrderService;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.test.context.support.WithMockUser;

class AiOpenPurchaseOrderUnionIntegrationTests extends AbstractIntegrationTest {
    @Autowired private PurchaseOrderService purchaseOrders;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private SupplierRepository suppliers;
    @Autowired private MaterialRepository materials;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @Test
    @WithMockUser(roles = "PROCUREMENT_STAFF")
    void oneOpenPoContainingTwoRequestedMaterialsIsCountedOnceAndMissingSetReturnsZero() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        Supplier supplier = suppliers.saveAndFlush(Supplier.create("SUP-AI-" + suffix, "AI PO Supplier", null,
                BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        User creator = users.saveAndFlush(User.create("ai-po-" + suffix.toLowerCase(), "hash", "AI PO User",
                UserRole.PROCUREMENT_STAFF, clock.instant()));
        Material first = materials.saveAndFlush(Material.create("MAT-AI-A-" + suffix, "AI Material A",
                BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        Material second = materials.saveAndFlush(Material.create("MAT-AI-B-" + suffix, "AI Material B",
                BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        Material withoutOpenPo = materials.saveAndFlush(Material.create("MAT-AI-C-" + suffix, "AI Material C",
                BaseUnit.KG, BigDecimal.ZERO, clock.instant()));

        PurchaseOrder order = PurchaseOrder.create(supplier, TestDataConstants.TEST_TODAY.plusDays(3), creator,
                clock.instant());
        order.assignPendingPoNumber("PO-AI-" + suffix);
        order.addItem(PurchaseOrderItem.create(first, BigDecimal.ONE, BigDecimal.TEN, clock.instant()));
        order.addItem(PurchaseOrderItem.create(second, BigDecimal.valueOf(2), BigDecimal.TEN, clock.instant()));
        PurchaseOrder saved = purchaseOrderRepository.saveAndFlush(order);
        jdbcTemplate.update("UPDATE purchase_orders SET status = ?, stage = ?, ordered_progress = ? WHERE id = ?",
                PurchaseOrderStatus.ORDERED.name(), PurchaseOrderStage.ORDER_FOLLOW_UP.name(),
                OrderedProgress.FOLLOW_UP.name(), saved.getId());

        var sharedOrderResult = purchaseOrders.listOpenByMaterialIds(List.of(first.getId(), second.getId()),
                PageRequest.of(0, 100, Sort.by(Sort.Direction.ASC, "id")));
        var emptyResult = purchaseOrders.listOpenByMaterialIds(List.of(withoutOpenPo.getId()),
                PageRequest.of(0, 100, Sort.by(Sort.Direction.ASC, "id")));

        assertThat(sharedOrderResult.totalElements()).isEqualTo(1L);
        assertThat(sharedOrderResult.content()).hasSize(1);
        assertThat(sharedOrderResult.content().getFirst().items()).extracting(item -> item.materialId())
                .containsExactly(first.getId(), second.getId());
        assertThat(emptyResult.content()).isEmpty();
        assertThat(emptyResult.totalElements()).isZero();
    }
}

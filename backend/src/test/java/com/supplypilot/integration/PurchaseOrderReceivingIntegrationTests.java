package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.recommendation.query.IncomingInventoryQuery;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class PurchaseOrderReceivingIntegrationTests extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private IncomingInventoryQuery incomingInventoryQuery;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private JwtTokenService tokens;
    private Supplier supplier;
    private User buyer;
    private User manager;
    private User warehouse;
    private User admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tokens = new JwtTokenService(jwtProperties, clock);
        supplier = supplierRepository.saveAndFlush(Supplier.create(
                "SUP-612-" + suffix(), "Receiving Supplier", null,
                BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        buyer = user(UserRole.PROCUREMENT_STAFF);
        manager = user(UserRole.PROCUREMENT_MANAGER);
        warehouse = user(UserRole.WAREHOUSE_STAFF);
        admin = user(UserRole.ADMIN);
    }

    @Test
    void warehouseFullReceivingAtomicallyUpdatesThreeInventoriesTransactionsAndAudit() throws Exception {
        Material first = material("A", "100");
        Material second = material("B", "200");
        Material third = material("C", "300");
        PurchaseOrder order = orderedOrder(quantities(first, "10", second, "20", third, "30"));
        assertThat(incomingInventoryQuery.calculateIncomingQuantity(
                first.getId(), TestDataConstants.TEST_TODAY.plusDays(1))).isEqualByComparingTo("10");

        receive(warehouse, order.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedBy").value(warehouse.getId()))
                .andExpect(jsonPath("$.receivedByDisplayName").value(warehouse.getDisplayName()))
                .andExpect(jsonPath("$.receivedAt").value(clock.instant().toString()))
                .andExpect(jsonPath("$.approvedBy").value(manager.getId()))
                .andExpect(jsonPath("$.orderedBy").value(buyer.getId()))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.isOverdue").value(false));

        assertThat(quantity(first)).isEqualByComparingTo("110");
        assertThat(quantity(second)).isEqualByComparingTo("220");
        assertThat(quantity(third)).isEqualByComparingTo("330");
        List<Map<String, Object>> transactions = jdbcTemplate.queryForList("""
                SELECT material_id, quantity, purchase_order_id, created_by, created_at, reason, type
                FROM stock_transactions WHERE purchase_order_id = ? ORDER BY material_id
                """, order.getId());
        assertThat(transactions).hasSize(3).allSatisfy(row -> {
            assertThat(row.get("type")).isEqualTo("PURCHASE_RECEIVE");
            assertThat(((Number) row.get("purchase_order_id")).longValue()).isEqualTo(order.getId());
            assertThat(((Number) row.get("created_by")).longValue()).isEqualTo(warehouse.getId());
            assertThat(row.get("created_at")).isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            assertThat(row.get("reason")).isNull();
        });
        assertThat(transactions.stream().map(row -> ((BigDecimal) row.get("quantity"))))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30"));
        assertThat(incomingInventoryQuery.calculateIncomingQuantity(
                first.getId(), TestDataConstants.TEST_TODAY.plusDays(1))).isZero();

        mockMvc.perform(get("/api/v1/purchase-orders/{id}", order.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedBy").value(warehouse.getId()))
                .andExpect(jsonPath("$.receivedAt").value(clock.instant().toString()))
                .andExpect(jsonPath("$.isOverdue").value(false));
    }

    @Test
    void adminCanReceiveButOtherRolesAndAnonymousCannot() throws Exception {
        PurchaseOrder adminOrder = orderedOrder(Map.of(material("ADMIN", "0"), "2"));
        receive(admin, adminOrder.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedBy").value(admin.getId()));

        for (UserRole role : new UserRole[] {UserRole.PROCUREMENT_STAFF,
                UserRole.PROCUREMENT_MANAGER, UserRole.PRODUCTION_PLANNER}) {
            PurchaseOrder forbidden = orderedOrder(Map.of(material(role.name(), "0"), "2"));
            receive(user(role), forbidden.getId()).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            assertUnreceived(forbidden.getId());
        }

        PurchaseOrder anonymous = orderedOrder(Map.of(material("ANON", "0"), "2"));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/receive", anonymous.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertUnreceived(anonymous.getId());
    }

    @Test
    void rejectsNotFoundEveryNonOrderedStatusAndDuplicateWithoutSideEffects() throws Exception {
        receive(warehouse, 999999999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_FOUND"));

        for (String statusValue : new String[] {"DRAFT", "APPROVED", "REJECTED"}) {
            Material material = material(statusValue, "5");
            PurchaseOrder order = orderedOrder(Map.of(material, "4"));
            jdbcTemplate.update("UPDATE purchase_orders SET status = ? WHERE id = ?", statusValue, order.getId());
            receive(warehouse, order.getId()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS"));
            assertThat(quantity(material)).isEqualByComparingTo("5");
            assertThat(transactionCount(order.getId())).isZero();
        }

        Material material = material("DUP", "50");
        PurchaseOrder order = orderedOrder(Map.of(material, "7"));
        receive(warehouse, order.getId()).andExpect(status().isOk());
        receive(warehouse, order.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RECEIVING"));
        assertThat(quantity(material)).isEqualByComparingTo("57");
        assertThat(transactionCount(order.getId())).isEqualTo(1);
    }

    @Test
    void missingInventoryAndMissingOrInvalidItemsRollbackTheWholeReceive() throws Exception {
        Material present = material("PRESENT", "100");
        Material missing = material("MISSING", "200");
        inventoryRepository.delete(inventoryRepository.findByMaterialId(missing.getId()).orElseThrow());
        inventoryRepository.flush();
        PurchaseOrder missingInventory = orderedOrderWithoutCreatingInventory(
                Map.of(present, "10", missing, "20"));

        receive(warehouse, missingInventory.getId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
        assertThat(quantity(present)).isEqualByComparingTo("100");
        assertThat(transactionCount(missingInventory.getId())).isZero();
        assertUnreceived(missingInventory.getId());

        Material noItemsMaterial = material("EMPTY", "10");
        PurchaseOrder noItems = orderedOrder(Map.of(noItemsMaterial, "1"));
        jdbcTemplate.update("DELETE FROM purchase_order_items WHERE purchase_order_id = ?", noItems.getId());
        receive(warehouse, noItems.getId()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertUnreceived(noItems.getId());

        Material invalidMaterial = material("INVALID", "10");
        PurchaseOrder invalid = orderedOrder(Map.of(invalidMaterial, "1"));
        jdbcTemplate.execute("ALTER TABLE purchase_order_items DROP CHECK chk_purchase_order_items_quantity");
        try {
            jdbcTemplate.update("UPDATE purchase_order_items SET quantity = 0 WHERE purchase_order_id = ?", invalid.getId());
            receive(warehouse, invalid.getId()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
            assertThat(quantity(invalidMaterial)).isEqualByComparingTo("10");
            assertThat(transactionCount(invalid.getId())).isZero();
            assertUnreceived(invalid.getId());
        } finally {
            jdbcTemplate.update("UPDATE purchase_order_items SET quantity = 1 WHERE purchase_order_id = ?", invalid.getId());
            jdbcTemplate.execute("ALTER TABLE purchase_order_items ADD CONSTRAINT "
                    + "chk_purchase_order_items_quantity CHECK (quantity > 0)");
        }
    }

    @Test
    void thirdStockTransactionDatabaseFailureRollsBackEarlierFlushedWorkAndPoAudit() throws Exception {
        Material first = material("ROLL-A", "100");
        Material second = material("ROLL-B", "200");
        Material third = material("ROLL-C", "300");
        PurchaseOrder order = orderedOrder(quantities(first, "10", second, "20", third, "30"));
        jdbcTemplate.execute("ALTER TABLE stock_transactions ADD CONSTRAINT chk_sp612_third_failure "
                + "CHECK (material_id <> " + third.getId() + ")");
        try {
            receive(warehouse, order.getId()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE stock_transactions DROP CHECK chk_sp612_third_failure");
        }

        assertThat(quantity(first)).isEqualByComparingTo("100");
        assertThat(quantity(second)).isEqualByComparingTo("200");
        assertThat(quantity(third)).isEqualByComparingTo("300");
        assertThat(transactionCount(order.getId())).isZero();
        assertUnreceived(order.getId());
    }

    private PurchaseOrder orderedOrder(Map<Material, String> quantities) {
        for (Material material : quantities.keySet()) {
            if (inventoryRepository.findByMaterialId(material.getId()).isEmpty()) {
                inventoryRepository.saveAndFlush(Inventory.create(material, clock.instant()));
            }
        }
        return orderedOrderWithoutCreatingInventory(quantities);
    }

    private Map<Material, String> quantities(Object... values) {
        Map<Material, String> quantities = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            quantities.put((Material) values[index], (String) values[index + 1]);
        }
        return quantities;
    }

    private PurchaseOrder orderedOrderWithoutCreatingInventory(Map<Material, String> quantities) {
        PurchaseOrder order = PurchaseOrder.create(
                supplier, TestDataConstants.TEST_TODAY.plusDays(1), buyer, clock.instant());
        order.assignPendingPoNumber("P-" + suffix());
        quantities.forEach((material, quantity) -> order.addItem(PurchaseOrderItem.create(
                material, new BigDecimal(quantity), BigDecimal.TEN, clock.instant())));
        order.approve(manager, clock.instant());
        order.markOrdered(buyer, clock.instant());
        PurchaseOrder persisted = purchaseOrderRepository.saveAndFlush(order);
        persisted.assignDisplayPoNumber("PO-612-" + persisted.getId());
        return purchaseOrderRepository.saveAndFlush(persisted);
    }

    private Material material(String marker, String initialQuantity) {
        Material material = materialRepository.saveAndFlush(Material.create(
                "MAT-612-" + marker + "-" + suffix(), marker, BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        inventoryRepository.saveAndFlush(Inventory.create(material, clock.instant()));
        if (!"0".equals(initialQuantity)) {
            Inventory inventory = inventoryRepository.findByMaterialId(material.getId()).orElseThrow();
            inventory.adjust(new BigDecimal(initialQuantity), clock.instant());
            inventoryRepository.saveAndFlush(inventory);
        }
        return material;
    }

    private ResultActions receive(User actor, long purchaseOrderId) throws Exception {
        return mockMvc.perform(post("/api/v1/purchase-orders/{id}/receive", purchaseOrderId)
                .header("Authorization", bearer(actor)));
    }

    private BigDecimal quantity(Material material) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory WHERE material_id = ?", BigDecimal.class, material.getId());
    }

    private int transactionCount(long purchaseOrderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_transactions WHERE purchase_order_id = ?",
                Integer.class, purchaseOrderId);
    }

    private void assertUnreceived(long purchaseOrderId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, received_by, received_at FROM purchase_orders WHERE id = ?", purchaseOrderId);
        assertThat(row.get("status")).isNotEqualTo("RECEIVED");
        assertThat(row.get("received_by")).isNull();
        assertThat(row.get("received_at")).isNull();
    }

    private User user(UserRole role) {
        return userRepository.saveAndFlush(User.create(
                "sp612-" + role.name().toLowerCase() + "-" + suffix(), "hash", role.name(), role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(
                user.getId(), user.getUsername(), user.getRole()));
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

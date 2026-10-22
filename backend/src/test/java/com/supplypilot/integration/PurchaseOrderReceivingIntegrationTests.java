package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
    @Autowired private ObjectMapper objectMapper;

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
        prepareAcceptedInspection(admin, order.getId());
        assertThat(incomingInventoryQuery.calculateIncomingQuantity(
                first.getId(), TestDataConstants.TEST_TODAY.plusDays(1))).isEqualByComparingTo("10");

        MvcResult receiveResult = receive(warehouse, order.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedBy").value(warehouse.getId()))
                .andExpect(jsonPath("$.receivedByDisplayName").value(warehouse.getDisplayName()))
                .andExpect(jsonPath("$.receivedAt").value(clock.instant().toString()))
                .andExpect(jsonPath("$.approvedBy").doesNotExist())
                .andExpect(jsonPath("$.orderedBy").doesNotExist())
                .andExpect(jsonPath("$.items[0].unitPrice").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.isOverdue").value(false)).andReturn();
        assertReceivingContract(objectMapper.readTree(receiveResult.getResponse().getContentAsString()));

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
        receiveCancel(admin, order.getId(), "receiving is complete").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class,
                order.getId())).isEqualTo("RECEIVED");
        assertThat(quantity(first)).isEqualByComparingTo("110");
        assertThat(transactionCount(order.getId())).isEqualTo(3);
    }

    @Test
    void inspectionConfirmationIsPersistedBeforeReceivingAndDoesNotChangeInventory() throws Exception {
        Material material = material("INSPECT", "25");
        PurchaseOrder order = orderedOrder(Map.of(material, "5"));

        receive(admin, order.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIVING_INSPECTION_INCOMPLETE"));

        mockMvc.perform(post("/api/v1/purchase-orders/{id}/begin-receiving-inspection", order.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ORDERED"))
                .andExpect(jsonPath("$.orderedProgress").value("RECEIVING_INSPECTION"));

        mockMvc.perform(put("/api/v1/purchase-orders/{id}/receiving-inspection", order.getId())
                        .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allItemsReceived\":false,\"allQuantitiesConfirmed\":false,"
                                + "\"allQualityAccepted\":false,\"note\":\"尚未到貨，尚未清點\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ORDERED"))
                .andExpect(jsonPath("$.orderedProgress").value("RECEIVING_INSPECTION"))
                .andExpect(jsonPath("$.receivingInspection.allItemsReceived").value(false))
                .andExpect(jsonPath("$.receivingInspection.note").value("尚未到貨，尚未清點"));

        assertThat(quantity(material)).isEqualByComparingTo("25");
        assertThat(transactionCount(order.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class,
                order.getId())).isEqualTo("ORDERED");

        mockMvc.perform(get("/api/v1/purchase-orders/{id}", order.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivingInspection.note").value("尚未到貨，尚未清點"));

        recordInspection(admin, order.getId(), false, false, false, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.receivingInspection.allItemsReceived").value(false))
                .andExpect(jsonPath("$.receivingInspection.allQuantitiesConfirmed").value(false))
                .andExpect(jsonPath("$.receivingInspection.allQualityAccepted").value(false));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT inspection_note FROM purchase_orders WHERE id = ?", String.class, order.getId())).isNull();
        receive(admin, order.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIVING_INSPECTION_INCOMPLETE"));

        for (boolean[] result : List.of(new boolean[] {false, true, true}, new boolean[] {true, false, true},
                new boolean[] {true, true, false})) {
            recordInspection(admin, order.getId(), result[0], result[1], result[2], "驗收異常").andExpect(status().isOk());
            receive(admin, order.getId()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RECEIVING_INSPECTION_INCOMPLETE"));
        }
        recordInspection(admin, order.getId(), true, true, true, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.receivingInspection.allItemsReceived").value(true))
                .andExpect(jsonPath("$.receivingInspection.allQuantitiesConfirmed").value(true))
                .andExpect(jsonPath("$.receivingInspection.allQualityAccepted").value(true))
                .andExpect(jsonPath("$.receivingInspection.inspectedBy").value(admin.getId()));

        receive(admin, order.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));
        assertThat(quantity(material)).isEqualByComparingTo("30");
        assertThat(transactionCount(order.getId())).isEqualTo(1);
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/confirm-payment", order.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.paymentConfirmed").value(true))
                .andExpect(jsonPath("$.paymentConfirmedBy").value(admin.getId()))
                .andExpect(jsonPath("$.paymentConfirmedAt").value(clock.instant().toString()));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/confirm-payment", order.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/revoke-payment-confirmation", order.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.paymentConfirmed").value(false))
                .andExpect(jsonPath("$.paymentConfirmedBy").doesNotExist())
                .andExpect(jsonPath("$.paymentConfirmedAt").doesNotExist());
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/revoke-payment-confirmation", order.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));
        assertThat(quantity(material)).isEqualByComparingTo("30");
        assertThat(transactionCount(order.getId())).isEqualTo(1);
    }

    @Test
    void rejectsIncompleteNullAndUnknownInspectionRequestsWithoutChangingAnyState() throws Exception {
        Material material = material("INVALID-INSPECTION", "25");
        PurchaseOrder order = orderedOrder(Map.of(material, "5"));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/begin-receiving-inspection", order.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        Map<String, Object> before = jdbcTemplate.queryForMap("""
                SELECT status, ordered_progress, inspection_all_items_received,
                       inspection_all_quantities_confirmed, inspection_all_quality_accepted, inspection_note,
                       inspected_by, inspected_by_user_id_snapshot, inspected_by_username_snapshot,
                       inspected_by_display_name_snapshot, inspected_at, updated_at, version
                FROM purchase_orders WHERE id = ?
                """, order.getId());
        BigDecimal inventoryBefore = quantity(material);
        int transactionsBefore = transactionCount(order.getId());
        List<String> invalidBodies = List.of(
                "{}",
                "{\"allQuantitiesConfirmed\":true,\"allQualityAccepted\":true}",
                "{\"allItemsReceived\":true,\"allQualityAccepted\":true}",
                "{\"allItemsReceived\":true,\"allQuantitiesConfirmed\":true}",
                "{\"allItemsReceived\":null,\"allQuantitiesConfirmed\":true,\"allQualityAccepted\":true}",
                "{\"allItemsReceived\":true,\"allQuantitiesConfirmed\":null,\"allQualityAccepted\":true}",
                "{\"allItemsReceived\":true,\"allQuantitiesConfirmed\":true,\"allQualityAccepted\":null}",
                "{\"allItemsReceived\":true,\"allQuantitiesConfirmed\":true,"
                        + "\"allQualityAccepted\":true,\"unknownStage\":\"RECEIVED\"}");

        for (String body : invalidBodies) {
            mockMvc.perform(put("/api/v1/purchase-orders/{id}/receiving-inspection", order.getId())
                            .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            Map<String, Object> after = jdbcTemplate.queryForMap("""
                    SELECT status, ordered_progress, inspection_all_items_received,
                           inspection_all_quantities_confirmed, inspection_all_quality_accepted, inspection_note,
                           inspected_by, inspected_by_user_id_snapshot, inspected_by_username_snapshot,
                           inspected_by_display_name_snapshot, inspected_at, updated_at, version
                    FROM purchase_orders WHERE id = ?
                    """, order.getId());
            assertThat(after).isEqualTo(before);
            assertThat(quantity(material)).isEqualByComparingTo(inventoryBefore);
            assertThat(transactionCount(order.getId())).isEqualTo(transactionsBefore);
        }
    }

    @Test
    void adminCanReceiveButOtherRolesAndAnonymousCannot() throws Exception {
        PurchaseOrder adminOrder = orderedOrder(Map.of(material("ADMIN", "0"), "2"));
        prepareAcceptedInspection(admin, adminOrder.getId());
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
    void inspectionAndPaymentCommandsRejectAnonymousAndWarehouseUsersWithoutMutation() throws Exception {
        PurchaseOrder inspectionOrder = orderedOrder(Map.of(material("INSPECTION-AUTH", "20"), "3"));
        var beginPath = "/api/v1/purchase-orders/" + inspectionOrder.getId() + "/begin-receiving-inspection";
        var recordPath = "/api/v1/purchase-orders/" + inspectionOrder.getId() + "/receiving-inspection";
        mockMvc.perform(post(beginPath)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(recordPath).contentType(MediaType.APPLICATION_JSON)
                .content("{\"allItemsReceived\":true,\"allQuantitiesConfirmed\":true,"
                        + "\"allQualityAccepted\":true,\"note\":null}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(beginPath).header("Authorization", bearer(warehouse)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(recordPath).header("Authorization", bearer(warehouse))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allItemsReceived\":true,\"allQuantitiesConfirmed\":true,"
                        + "\"allQualityAccepted\":true,\"note\":null}"))
                .andExpect(status().isForbidden());
        Map<String, Object> unchangedInspection = jdbcTemplate.queryForMap(
                "SELECT status, ordered_progress, inspection_all_items_received, inspected_at "
                        + "FROM purchase_orders WHERE id = ?", inspectionOrder.getId());
        assertThat(unchangedInspection.get("status")).isEqualTo("ORDERED");
        assertThat(unchangedInspection.get("ordered_progress")).isEqualTo("FOLLOW_UP");
        assertThat(unchangedInspection.get("inspection_all_items_received")).isEqualTo(false);
        assertThat(unchangedInspection.get("inspected_at")).isNull();

        Material unpaidMaterial = material("PAYMENT-AUTH", "30");
        PurchaseOrder unpaid = orderedOrder(Map.of(unpaidMaterial, "4"));
        prepareAcceptedInspection(admin, unpaid.getId());
        receive(admin, unpaid.getId()).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/confirm-payment", unpaid.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/confirm-payment", unpaid.getId())
                .header("Authorization", bearer(warehouse))).andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payment_confirmed_at FROM purchase_orders WHERE id = ?", Object.class, unpaid.getId()))
                .isNull();

        Material paidMaterial = material("REVOKE-AUTH", "40");
        PurchaseOrder paid = orderedOrder(Map.of(paidMaterial, "5"));
        prepareAcceptedInspection(admin, paid.getId());
        receive(admin, paid.getId()).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/confirm-payment", paid.getId())
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/revoke-payment-confirmation", paid.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/revoke-payment-confirmation", paid.getId())
                .header("Authorization", bearer(warehouse))).andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payment_confirmed_at FROM purchase_orders WHERE id = ?", Object.class, paid.getId()))
                .isNotNull();
        assertThat(quantity(unpaidMaterial)).isEqualByComparingTo("34");
        assertThat(quantity(paidMaterial)).isEqualByComparingTo("45");
        assertThat(transactionCount(unpaid.getId())).isEqualTo(1);
        assertThat(transactionCount(paid.getId())).isEqualTo(1);
    }

    @Test
    void rejectsNotFoundEveryNonOrderedStatusAndDuplicateWithoutSideEffects() throws Exception {
        receive(warehouse, 999999999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_FOUND"));

        for (String statusValue : new String[] {"DRAFT", "APPROVED", "REJECTED"}) {
            Material material = material(statusValue, "5");
            PurchaseOrder order = orderedOrder(Map.of(material, "4"));
            jdbcTemplate.update("UPDATE purchase_orders SET status = ?, ordered_progress = NULL WHERE id = ?",
                    statusValue, order.getId());
            receive(warehouse, order.getId()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS"));
            assertThat(quantity(material)).isEqualByComparingTo("5");
            assertThat(transactionCount(order.getId())).isZero();
        }

        Material material = material("DUP", "50");
        PurchaseOrder order = orderedOrder(Map.of(material, "7"));
        prepareAcceptedInspection(admin, order.getId());
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
        prepareAcceptedInspection(admin, missingInventory.getId());

        receive(warehouse, missingInventory.getId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
        assertThat(quantity(present)).isEqualByComparingTo("100");
        assertThat(transactionCount(missingInventory.getId())).isZero();
        assertUnreceived(missingInventory.getId());

        Material noItemsMaterial = material("EMPTY", "10");
        PurchaseOrder noItems = orderedOrder(Map.of(noItemsMaterial, "1"));
        prepareAcceptedInspection(admin, noItems.getId());
        jdbcTemplate.update("DELETE FROM purchase_order_items WHERE purchase_order_id = ?", noItems.getId());
        receive(warehouse, noItems.getId()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertUnreceived(noItems.getId());

        Material invalidMaterial = material("INVALID", "10");
        PurchaseOrder invalid = orderedOrder(Map.of(invalidMaterial, "1"));
        prepareAcceptedInspection(admin, invalid.getId());
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
        prepareAcceptedInspection(admin, order.getId());
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

    @Test
    void cancelledOrderIsExcludedFromIncomingAndCannotReceiveOrTouchInventory() throws Exception {
        Material material = material("CANCELLED", "80");
        PurchaseOrder order = orderedOrder(Map.of(material, "12"));
        receiveCancel(admin, order.getId(), "plan changed").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(incomingInventoryQuery.calculateIncomingQuantity(
                material.getId(), TestDataConstants.TEST_TODAY.plusDays(2))).isZero();
        receive(warehouse, order.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS"));
        assertThat(quantity(material)).isEqualByComparingTo("80");
        assertThat(transactionCount(order.getId())).isZero();
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

    private void prepareAcceptedInspection(User actor, long purchaseOrderId) throws Exception {
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/begin-receiving-inspection", purchaseOrderId)
                        .header("Authorization", bearer(actor)))
                .andExpect(status().isOk());
        recordInspection(actor, purchaseOrderId, true, true, true, "").andExpect(status().isOk());
    }

    private ResultActions recordInspection(User actor, long purchaseOrderId, boolean arrived,
            boolean quantitiesConfirmed, boolean qualityAccepted, String note) throws Exception {
        String noteJson = note == null ? "null" : "\"" + note + "\"";
        return mockMvc.perform(put("/api/v1/purchase-orders/{id}/receiving-inspection", purchaseOrderId)
                .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"allItemsReceived\":" + arrived + ",\"allQuantitiesConfirmed\":" + quantitiesConfirmed
                        + ",\"allQualityAccepted\":" + qualityAccepted + ",\"note\":" + noteJson + "}"));
    }

    private ResultActions receiveCancel(User actor, long purchaseOrderId, String reason) throws Exception {
        return mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel", purchaseOrderId)
                .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}"));
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

    private void assertReceivingContract(JsonNode response) {
        assertThat(fieldNames(response)).containsExactlyInAnyOrder("id", "poNumber", "supplierId", "supplierCode",
                "supplierName", "status", "orderedProgress", "receivingInspection", "expectedDeliveryDate", "isOverdue",
                "items", "orderedAt", "receivedBy", "receivedByUsername", "receivedByDisplayName", "receivedAt");
        response.path("items").forEach(item -> assertThat(fieldNames(item)).containsExactlyInAnyOrder("id", "materialId",
                "materialCode", "materialName", "quantity", "unit"));
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

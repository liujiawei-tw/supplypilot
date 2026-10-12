package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.domain.StockTransaction;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.inventory.repository.StockTransactionRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class StockTransactionControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Clock clock;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private StockTransactionRepository transactionRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private User warehouse;
    private User manager;
    private User admin;
    private User procurementStaff;
    private User planner;
    private Material kg;
    private Material pcs;
    private StockTransaction positive;
    private StockTransaction negative;
    private PurchaseOrder receivedOrder;

    @BeforeEach
    void setUp() throws Exception {
        warehouse = user(UserRole.WAREHOUSE_STAFF);
        manager = user(UserRole.PROCUREMENT_MANAGER);
        admin = user(UserRole.ADMIN);
        procurementStaff = user(UserRole.PROCUREMENT_STAFF);
        planner = user(UserRole.PRODUCTION_PLANNER);
        kg = material("KG", BaseUnit.KG);
        pcs = material("PCS", BaseUnit.PCS);
        Inventory kgInventory = Inventory.create(kg, clock.instant());
        inventoryRepository.saveAndFlush(kgInventory);
        Inventory pcsInventory = Inventory.create(pcs, clock.instant());
        pcsInventory.adjust(new BigDecimal("10"), clock.instant());
        inventoryRepository.saveAndFlush(pcsInventory);

        positive = adjust(warehouse, kg, "5", "restocked");
        negative = adjust(warehouse, pcs, "-2", "damaged");
        receivedOrder = createAndReceivePurchaseOrder(pcs, warehouse);
    }

    @Test
    void enforcesCompleteRoleMatrixForListAndDetail() throws Exception {
        for (User actor : List.of(warehouse, manager, admin)) {
            getList(actor).andExpect(status().isOk());
            getDetail(actor, positive.getId()).andExpect(status().isOk());
        }
        for (User actor : List.of(procurementStaff, planner)) {
            getList(actor).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            getDetail(actor, positive.getId()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        getList(null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        getDetail(null, positive.getId()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        getDetail(admin, 999999999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_TRANSACTION_NOT_FOUND"));

        // The new role grant must not change the existing Inventory read boundary.
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void returnsManualAndPurchaseReceiveDataWithStrictContracts() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/v1/stock-transactions").param("materialId", pcs.getId().toString())
                        .header("Authorization", bearer(warehouse))).andExpect(status().isOk()).andReturn();
        JsonNode content = objectMapper.readTree(list.getResponse().getContentAsString()).path("content");
        assertThat(content.size()).isEqualTo(2);
        JsonNode manualNegative = find(content, negative.getId());
        JsonNode manualPositive = objectMapper.readTree(getDetail(warehouse, positive.getId()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode purchase = find(content, transactionRepository.findAll().stream()
                .filter(t -> receivedOrder.getId().equals(t.getPurchaseOrderId())).findFirst().orElseThrow().getId());
        assertContract(manualNegative);
        assertThat(manualNegative.path("quantity").decimalValue()).isEqualByComparingTo("-2");
        assertThat(manualNegative.path("unit").asText()).isEqualTo("PCS");
        assertThat(manualNegative.path("reason").asText()).isEqualTo("damaged");
        assertThat(manualPositive.path("quantity").decimalValue()).isEqualByComparingTo("5");
        assertThat(manualPositive.path("unit").asText()).isEqualTo("KG");
        assertThat(purchase.path("type").asText()).isEqualTo("PURCHASE_RECEIVE");
        assertThat(purchase.path("quantity").decimalValue()).isEqualByComparingTo("3");
        assertThat(purchase.path("purchaseOrderId").asLong()).isEqualTo(receivedOrder.getId());
        assertThat(purchase.path("purchaseOrderNumber").asText()).isEqualTo(receivedOrder.getPoNumber());
        assertThat(purchase.path("reason").isNull()).isTrue();
        assertThat(purchase.path("createdBy").asLong()).isEqualTo(warehouse.getId());
        assertThat(purchase.path("createdByDisplayName").asText()).isEqualTo(warehouse.getDisplayName());
        assertThat(purchase.path("createdAt").asText()).isEqualTo(clock.instant().toString());

        Set<String> expected = Set.of("id", "materialId", "materialCode", "materialName", "unit", "type", "quantity",
                "purchaseOrderId", "purchaseOrderNumber", "reason", "createdBy", "createdByDisplayName", "createdAt");
        assertThat(fieldNames(manualNegative)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(fieldNames(manualPositive)).containsExactlyInAnyOrderElementsOf(expected);
        JsonNode detail = objectMapper.readTree(getDetail(warehouse, negative.getId()).andReturn().getResponse().getContentAsString());
        assertThat(fieldNames(detail)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void filtersInclusivelyPaginatesAndValidatesAllQueryParameters() throws Exception {
        String materialId = kg.getId().toString();
        mockMvc.perform(get("/api/v1/stock-transactions").param("materialId", materialId)
                        .header("Authorization", bearer(warehouse))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/stock-transactions").param("type", "MANUAL_ADJUSTMENT")
                        .param("materialId", materialId).param("from", clock.instant().minusSeconds(1).toString())
                        .param("to", clock.instant().plusSeconds(1).toString()).header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/stock-transactions").param("purchaseOrderId", receivedOrder.getId().toString())
                        .header("Authorization", bearer(warehouse))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].purchaseOrderNumber").value(receivedOrder.getPoNumber()));
        mockMvc.perform(get("/api/v1/stock-transactions").param("from", clock.instant().plusSeconds(1).toString())
                        .param("to", clock.instant().minusSeconds(1).toString()).header("Authorization", bearer(warehouse)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (String query : List.of("materialId=0", "purchaseOrderId=-1", "type=INVALID", "from=bad-instant",
                "page=-1", "size=0", "size=101", "sort=bad,asc", "sort=id,sideways", "sort=id,asc,extra")) {
            mockMvc.perform(get("/api/v1/stock-transactions?" + query).header("Authorization", bearer(warehouse)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get("/api/v1/stock-transactions").param("materialId", pcs.getId().toString())
                        .param("page", "0").param("size", "1")
                        .header("Authorization", bearer(warehouse))).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/stock-transactions").param("materialId", pcs.getId().toString())
                        .param("page", "2").param("size", "1")
                        .header("Authorization", bearer(warehouse))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void supportsAllSortFieldsAndStableIdTieBreak() throws Exception {
        for (String sort : List.of("id,asc", "createdAt,desc", "materialId,asc", "type,asc", "purchaseOrderId,asc", "quantity,desc")) {
            mockMvc.perform(get("/api/v1/stock-transactions").param("sort", sort)
                            .header("Authorization", bearer(warehouse))).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/stock-transactions").param("materialId", kg.getId().toString())
                        .param("sort", "materialId,asc")
                        .header("Authorization", bearer(warehouse))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].materialId").value(kg.getId()));
    }

    @Test
    void usesBoundedQueryCountForDifferentPageSizes() throws Exception {
        for (int i = 0; i < 6; i++) adjust(warehouse, kg, Integer.toString(i + 1), "batch-" + i);
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().clear();
        mockMvc.perform(get("/api/v1/stock-transactions").param("size", "1")
                .header("Authorization", bearer(warehouse))).andExpect(status().isOk());
        long oneRow = sessionFactory.getStatistics().getPrepareStatementCount();
        sessionFactory.getStatistics().clear();
        mockMvc.perform(get("/api/v1/stock-transactions").param("size", "20")
                .header("Authorization", bearer(warehouse))).andExpect(status().isOk());
        long manyRows = sessionFactory.getStatistics().getPrepareStatementCount();
        assertThat(manyRows).isLessThanOrEqualTo(oneRow + 2);
    }

    private org.springframework.test.web.servlet.ResultActions getList(User actor) throws Exception {
        var request = get("/api/v1/stock-transactions");
        if (actor == null) return mockMvc.perform(request);
        return mockMvc.perform(request.header("Authorization", bearer(actor)));
    }

    private org.springframework.test.web.servlet.ResultActions getDetail(User actor, Long id) throws Exception {
        var request = get("/api/v1/stock-transactions/{id}", id);
        if (actor == null) return mockMvc.perform(request);
        return mockMvc.perform(request.header("Authorization", bearer(actor)));
    }

    private StockTransaction adjust(User actor, Material material, String quantity, String reason) throws Exception {
        mockMvc.perform(post("/api/v1/inventory/{id}/adjustments", material.getId())
                        .header("Authorization", bearer(actor)).contentType("application/json")
                        .content("{\"quantity\":" + quantity + ",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk());
        return transactionRepository.findAll().stream().filter(t -> t.getMaterial().getId().equals(material.getId())
                && reason.equals(t.getReason())).findFirst().orElseThrow();
    }

    private PurchaseOrder createAndReceivePurchaseOrder(Material material, User receiver) throws Exception {
        Supplier supplier = supplierRepository.saveAndFlush(Supplier.create("SUP-402-" + suffix(), "Supplier", null,
                BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        PurchaseOrder order = PurchaseOrder.create(supplier, LocalDate.now(clock).plusDays(1), procurementStaff, clock.instant());
        order.assignPendingPoNumber("P-402-" + suffix());
        order.addItem(PurchaseOrderItem.create(material, new BigDecimal("3"), BigDecimal.ONE, clock.instant()));
        order.approve(admin, clock.instant());
        order.markOrdered(procurementStaff, clock.instant());
        PurchaseOrder persisted = purchaseOrderRepository.saveAndFlush(order);
        persisted.assignDisplayPoNumber("PO-402-" + persisted.getId());
        purchaseOrderRepository.saveAndFlush(persisted);
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/receive", persisted.getId())
                        .header("Authorization", bearer(receiver))).andExpect(status().isOk());
        return purchaseOrderRepository.findById(persisted.getId()).orElseThrow();
    }

    private Material material(String prefix, BaseUnit unit) {
        return materialRepository.saveAndFlush(Material.create("ST-402-" + prefix + "-" + suffix(), prefix,
                unit, BigDecimal.ZERO, clock.instant()));
    }

    private User user(UserRole role) {
        return userRepository.saveAndFlush(User.create("st-402-" + role.name() + "-" + suffix(), "hash",
                role.name(), role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private JsonNode find(JsonNode content, long id) {
        for (JsonNode node : content) if (node.path("id").asLong() == id) return node;
        throw new AssertionError("Transaction not found: " + id);
    }

    private void assertContract(JsonNode node) {
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("id", "materialId", "materialCode", "materialName", "unit",
                "type", "quantity", "purchaseOrderId", "purchaseOrderNumber", "reason", "createdBy",
                "createdByDisplayName", "createdAt");
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
}

package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.integration.support.PurchaseOrderReceivingConcurrencyTestConfiguration;
import com.supplypilot.integration.support.PurchaseOrderReceivingConcurrencyTestConfiguration.PurchaseOrderRead;
import com.supplypilot.integration.support.PurchaseOrderReceivingConcurrencyTestConfiguration.PurchaseOrderReceivingProbe;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
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
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(PurchaseOrderReceivingConcurrencyTestConfiguration.class)
class PurchaseOrderConcurrentReceivingIntegrationTests extends AbstractIntegrationTest {
    private static final long FUTURE_TIMEOUT_SECONDS = 30;

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PurchaseOrderReceivingProbe concurrencyProbe;

    private MockMvc mockMvc;
    private JwtTokenService tokens;
    private Supplier supplier;
    private User buyer;
    private User manager;
    private User firstWarehouseUser;
    private User secondWarehouseUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tokens = new JwtTokenService(jwtProperties, clock);
        supplier = supplierRepository.saveAndFlush(Supplier.create(
                "SUP-613-" + suffix(), "Concurrent Receiving Supplier", null,
                BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        buyer = user(UserRole.PROCUREMENT_STAFF);
        manager = user(UserRole.PROCUREMENT_MANAGER);
        firstWarehouseUser = user(UserRole.WAREHOUSE_STAFF);
        secondWarehouseUser = user(UserRole.WAREHOUSE_STAFF);
    }

    @RepeatedTest(3)
    void concurrentRequestsReadTheSamePoVersionAndOnlyOneReceiveCommits() throws Exception {
        Material first = material("A", "100");
        Material second = material("B", "200");
        Material third = material("C", "300");
        Map<Material, String> receivedQuantities = quantities(first, "10", second, "20", third, "30");
        PurchaseOrder order = orderedOrder(receivedQuantities);
        long purchaseOrderVersionBefore = order.getVersion();
        Map<Long, Long> inventoryVersionsBefore = inventoryVersions(receivedQuantities.keySet());
        int stockTransactionsBefore = transactionCount(order.getId());

        MvcResult firstResult;
        MvcResult secondResult;
        List<PurchaseOrderRead> sharedReads;
        List<String> failureTypes;
        try (PurchaseOrderReceivingProbe.Session session = concurrencyProbe.open(order.getId())) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<MvcResult> firstRequest = executor.submit(() -> receive(firstWarehouseUser, order.getId()));
                Future<MvcResult> secondRequest = executor.submit(() -> receive(secondWarehouseUser, order.getId()));
                firstResult = firstRequest.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                secondResult = secondRequest.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                sharedReads = session.reads();
                failureTypes = session.failureTypes();
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }

        assertThat(List.of(firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
                .containsExactlyInAnyOrder(200, 409);
        MvcResult successfulResult = firstResult.getResponse().getStatus() == 200 ? firstResult : secondResult;
        MvcResult staleResult = firstResult.getResponse().getStatus() == 409 ? firstResult : secondResult;
        User successfulReceiver = firstResult.getResponse().getStatus() == 200
                ? firstWarehouseUser : secondWarehouseUser;

        assertThat(sharedReads).hasSize(2).allSatisfy(read -> {
            assertThat(read.version()).isEqualTo(purchaseOrderVersionBefore);
            assertThat(read.status()).isEqualTo(PurchaseOrderStatus.ORDERED);
        });
        assertThat(failureTypes).singleElement().asString()
                .contains("ObjectOptimisticLockingFailureException", PurchaseOrder.class.getName());

        JsonNode successfulBody = objectMapper.readTree(successfulResult.getResponse().getContentAsString());
        assertThat(successfulBody.path("status").asText()).isEqualTo("RECEIVED");
        assertThat(successfulBody.path("receivedBy").asLong()).isEqualTo(successfulReceiver.getId());

        JsonNode staleBody = objectMapper.readTree(staleResult.getResponse().getContentAsString());
        assertThat(staleBody.path("code").asText()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(staleBody.path("traceId").asText()).isNotBlank();
        assertThat(staleBody.path("timestamp").asText()).isNotBlank();
        assertThat(staleBody.path("path").asText())
                .isEqualTo("/api/v1/purchase-orders/" + order.getId() + "/receive");

        Map<String, Object> finalOrder = jdbcTemplate.queryForMap("""
                SELECT status, version, received_by, received_at
                FROM purchase_orders WHERE id = ?
                """, order.getId());
        assertThat(finalOrder.get("status")).isEqualTo("RECEIVED");
        assertThat(((Number) finalOrder.get("version")).longValue()).isEqualTo(purchaseOrderVersionBefore + 1);
        assertThat(((Number) finalOrder.get("received_by")).longValue()).isEqualTo(successfulReceiver.getId());
        assertThat(finalOrder.get("received_at")).isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));

        assertThat(quantity(first)).isEqualByComparingTo("110");
        assertThat(quantity(second)).isEqualByComparingTo("220");
        assertThat(quantity(third)).isEqualByComparingTo("330");
        receivedQuantities.keySet().forEach(material -> assertThat(inventoryVersion(material))
                .isEqualTo(inventoryVersionsBefore.get(material.getId()) + 1));

        List<Map<String, Object>> transactions = jdbcTemplate.queryForList("""
                SELECT material_id, quantity, purchase_order_id, created_by, type
                FROM stock_transactions WHERE purchase_order_id = ? ORDER BY material_id
                """, order.getId());
        assertThat(transactions).hasSize(receivedQuantities.size()).allSatisfy(row -> {
            assertThat(row.get("type")).isEqualTo("PURCHASE_RECEIVE");
            assertThat(((Number) row.get("purchase_order_id")).longValue()).isEqualTo(order.getId());
            assertThat(((Number) row.get("created_by")).longValue()).isEqualTo(successfulReceiver.getId());
        });
        assertThat(transactionCount(order.getId())).isEqualTo(stockTransactionsBefore + receivedQuantities.size());

        MvcResult duplicateResult = receive(successfulReceiver, order.getId());
        assertThat(duplicateResult.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(duplicateResult.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("DUPLICATE_RECEIVING");
        assertThat(quantity(first)).isEqualByComparingTo("110");
        assertThat(quantity(second)).isEqualByComparingTo("220");
        assertThat(quantity(third)).isEqualByComparingTo("330");
        assertThat(transactionCount(order.getId())).isEqualTo(stockTransactionsBefore + receivedQuantities.size());
    }

    private MvcResult receive(User actor, long purchaseOrderId) throws Exception {
        return mockMvc.perform(post("/api/v1/purchase-orders/{id}/receive", purchaseOrderId)
                        .header("Authorization", bearer(actor)))
                .andReturn();
    }

    private PurchaseOrder orderedOrder(Map<Material, String> quantities) {
        PurchaseOrder order = PurchaseOrder.create(
                supplier, TestDataConstants.TEST_TODAY.plusDays(1), buyer, clock.instant());
        order.assignPendingPoNumber("P-" + suffix());
        quantities.forEach((material, quantity) -> order.addItem(PurchaseOrderItem.create(
                material, new BigDecimal(quantity), BigDecimal.TEN, clock.instant())));
        order.approve(manager, clock.instant());
        order.markOrdered(buyer, clock.instant());
        PurchaseOrder persisted = purchaseOrderRepository.saveAndFlush(order);
        persisted.assignDisplayPoNumber("PO-613-" + persisted.getId());
        return purchaseOrderRepository.saveAndFlush(persisted);
    }

    private Material material(String marker, String initialQuantity) {
        Material material = materialRepository.saveAndFlush(Material.create(
                "MAT-613-" + marker + "-" + suffix(), marker, BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        Inventory inventory = inventoryRepository.saveAndFlush(Inventory.create(material, clock.instant()));
        inventory.adjust(new BigDecimal(initialQuantity), clock.instant());
        inventoryRepository.saveAndFlush(inventory);
        return material;
    }

    private Map<Material, String> quantities(Object... values) {
        Map<Material, String> quantities = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            quantities.put((Material) values[index], (String) values[index + 1]);
        }
        return quantities;
    }

    private Map<Long, Long> inventoryVersions(Iterable<Material> materials) {
        Map<Long, Long> versions = new LinkedHashMap<>();
        materials.forEach(material -> versions.put(material.getId(), inventoryVersion(material)));
        return versions;
    }

    private BigDecimal quantity(Material material) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory WHERE material_id = ?", BigDecimal.class, material.getId());
    }

    private long inventoryVersion(Material material) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM inventory WHERE material_id = ?", Long.class, material.getId());
    }

    private int transactionCount(long purchaseOrderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_transactions WHERE purchase_order_id = ?",
                Integer.class, purchaseOrderId);
    }

    private User user(UserRole role) {
        return userRepository.saveAndFlush(User.create(
                "sp613-" + role.name().toLowerCase() + "-" + suffix(), "hash", role.name(), role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(
                user.getId(), user.getUsername(), user.getRole()));
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

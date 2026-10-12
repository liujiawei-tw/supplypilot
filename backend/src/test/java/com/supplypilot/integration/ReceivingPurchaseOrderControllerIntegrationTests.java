package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;

@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ReceivingPurchaseOrderControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private MockMvc mockMvc;
    private JwtTokenService tokens;
    private User buyer;
    private User warehouse;
    private User admin;
    private Supplier supplier;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tokens = new JwtTokenService(jwtProperties, clock);
        buyer = user(UserRole.PROCUREMENT_STAFF);
        warehouse = user(UserRole.WAREHOUSE_STAFF);
        admin = user(UserRole.ADMIN);
        supplier = supplierRepository.saveAndFlush(Supplier.create("SUP-615-" + suffix(), "Supplier", null,
                BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
    }

    @Test
    void warehouseReadsOnlyWhitelistedOrderedAndReceivedPurchaseOrders() throws Exception {
        Material includedMaterial = material("included");
        Material otherMaterial = material("other");
        PurchaseOrder ordered = order(includedMaterial, LocalDate.now(clock).minusDays(1), false);
        PurchaseOrder received = order(otherMaterial, LocalDate.now(clock).plusDays(2), true);
        PurchaseOrder draft = draft(material("draft"));

        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("materialId", includedMaterial.getId().toString())
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ordered.getId()))
                .andExpect(jsonPath("$.content[0].status").value("ORDERED"))
                .andExpect(jsonPath("$.content[0].isOverdue").value(true))
                .andExpect(jsonPath("$.content[0].items[0].unitPrice").doesNotExist())
                .andExpect(jsonPath("$.content[0].approvedBy").doesNotExist())
                .andExpect(jsonPath("$.content[0].createdBy").doesNotExist());

        mockMvc.perform(get("/api/v1/receiving/purchase-orders")
                        .param("status", "RECEIVED").param("materialId", otherMaterial.getId().toString())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(received.getId()))
                .andExpect(jsonPath("$.content[0].isOverdue").value(false))
                .andExpect(jsonPath("$.content[0].receivedBy").value(warehouse.getId()));

        mockMvc.perform(get("/api/v1/receiving/purchase-orders/{id}", received.getId())
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(received.getId()));
        mockMvc.perform(get("/api/v1/receiving/purchase-orders/{id}", draft.getId())
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_FOUND"));
    }

    @Test
    void validatesFiltersPaginationAndAuthorizationWithoutOpeningFullPurchaseOrderRead() throws Exception {
        Material sharedMaterial = material("shared");
        PurchaseOrder first = order(sharedMaterial, LocalDate.now(clock).plusDays(1), false);
        PurchaseOrder second = order(sharedMaterial, LocalDate.now(clock).plusDays(1), false);

        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("page", "0").param("size", "1")
                        .param("sort", "expectedDeliveryDate,desc").param("materialId", sharedMaterial.getId().toString())
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(first.getId()));
        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("status", "DRAFT")
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("orderedFrom", "2030-01-02")
                        .param("orderedTo", "2030-01-01").header("Authorization", bearer(warehouse)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("sort", "createdAt,asc")
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/receiving/purchase-orders").header("Authorization", bearer(buyer)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/receiving/purchase-orders"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/purchase-orders/{id}", second.getId()).header("Authorization", bearer(warehouse)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void enforcesReceivingVisibilityAndAuthorizationForEveryRole() throws Exception {
        PurchaseOrder ordered = order(material("ordered"), LocalDate.now(clock).plusDays(1), false);
        PurchaseOrder draft = draft(material("draft-status"));
        PurchaseOrder approved = persistedWithStatus(material("approved"), PurchaseOrderStatus.APPROVED);
        PurchaseOrder rejected = persistedWithStatus(material("rejected"), PurchaseOrderStatus.REJECTED);

        for (User actor : List.of(warehouse, admin)) {
            mockMvc.perform(get("/api/v1/receiving/purchase-orders").header("Authorization", bearer(actor)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/receiving/purchase-orders/{id}", ordered.getId())
                            .header("Authorization", bearer(actor)))
                    .andExpect(status().isOk());
        }
        for (UserRole role : List.of(UserRole.PROCUREMENT_STAFF, UserRole.PROCUREMENT_MANAGER, UserRole.PRODUCTION_PLANNER)) {
            User forbidden = user(role);
            mockMvc.perform(get("/api/v1/receiving/purchase-orders").header("Authorization", bearer(forbidden)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mockMvc.perform(get("/api/v1/receiving/purchase-orders/{id}", ordered.getId())
                            .header("Authorization", bearer(forbidden)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        for (long id : List.of(draft.getId(), approved.getId(), rejected.getId(), 999999999L)) {
            mockMvc.perform(get("/api/v1/receiving/purchase-orders/{id}", id).header("Authorization", bearer(warehouse)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_FOUND"));
        }
        mockMvc.perform(get("/api/v1/receiving/purchase-orders"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/receiving/purchase-orders/{id}", ordered.getId()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void validatesDateBoundariesAndAllInvalidQueryParameters() throws Exception {
        LocalDate today = LocalDate.now(clock);
        Material boundaryMaterial = material("boundary");
        PurchaseOrder order = order(boundaryMaterial, today.plusDays(3), false, Instant.parse("2026-10-12T23:30:00Z"));
        String id = boundaryMaterial.getId().toString();

        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("materialId", id)
                        .param("expectedDeliveryFrom", today.plusDays(3).toString())
                        .param("expectedDeliveryTo", today.plusDays(3).toString()).header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(order.getId()));
        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("materialId", id)
                        .param("orderedFrom", "2026-10-12").param("orderedTo", "2026-10-12")
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(order.getId()));
        for (String query : List.of(
                "expectedDeliveryFrom=2026-10-14&expectedDeliveryTo=2026-10-13",
                "orderedFrom=2026-10-14&orderedTo=2026-10-13",
                "expectedDeliveryFrom=not-a-date", "orderedFrom=not-a-date", "status=APPROVED",
                "page=-1", "size=0", "size=101", "sort=notAllowed,asc", "sort=id,sideways", "sort=id,asc,extra")) {
            mockMvc.perform(get("/api/v1/receiving/purchase-orders?" + query).header("Authorization", bearer(warehouse)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void returnsStrictResponseContractsAndUsesConstantQueryCountForPageSize() throws Exception {
        Material first = material("contract-first");
        Material second = material("contract-second");
        PurchaseOrder one = order(first, LocalDate.now(clock).minusDays(1), false);
        PurchaseOrder two = order(second, LocalDate.now(clock).plusDays(1), false);
        PurchaseOrder received = order(material("contract-received"), LocalDate.now(clock).minusDays(2), true);

        MvcResult list = mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("materialId", first.getId().toString())
                        .header("Authorization", bearer(warehouse))).andExpect(status().isOk()).andReturn();
        assertReceivingContract(objectMapper.readTree(list.getResponse().getContentAsString()).path("content").get(0));
        MvcResult detail = mockMvc.perform(get("/api/v1/receiving/purchase-orders/{id}", one.getId())
                        .header("Authorization", bearer(warehouse))).andExpect(status().isOk()).andReturn();
        assertReceivingContract(objectMapper.readTree(detail.getResponse().getContentAsString()));
        MvcResult receivedDetail = mockMvc.perform(get("/api/v1/receiving/purchase-orders/{id}", received.getId())
                        .header("Authorization", bearer(warehouse))).andExpect(status().isOk()).andReturn();
        JsonNode receivedNode = objectMapper.readTree(receivedDetail.getResponse().getContentAsString());
        assertReceivingContract(receivedNode);
        assertThat(receivedNode.path("isOverdue").asBoolean()).isFalse();

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().clear();
        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("materialId", second.getId().toString())
                        .header("Authorization", bearer(warehouse))).andExpect(status().isOk());
        long singleOrderStatements = sessionFactory.getStatistics().getPrepareStatementCount();

        Material manyItemsMaterial = material("many-items");
        for (int index = 0; index < 5; index++) order(manyItemsMaterial, LocalDate.now(clock).plusDays(1), false);
        sessionFactory.getStatistics().clear();
        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("materialId", manyItemsMaterial.getId().toString())
                        .param("size", "20").header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
        long multiOrderStatements = sessionFactory.getStatistics().getPrepareStatementCount();
        assertThat(multiOrderStatements).isLessThanOrEqualTo(singleOrderStatements + 1);
    }

    @Test
    void sortsAllWhitelistedFieldsAndCountsMultiItemPurchaseOrdersOnce() throws Exception {
        Material shared = material("sort-shared");
        Material extra = material("sort-extra");
        PurchaseOrder later = order(shared, LocalDate.of(2026, 10, 20), false, Instant.parse("2026-10-12T02:00:00Z"));
        PurchaseOrder earlier = order(shared, LocalDate.of(2026, 10, 15), false, Instant.parse("2026-10-11T02:00:00Z"));
        PurchaseOrder receivedLater = order(shared, LocalDate.of(2026, 10, 14), true, Instant.parse("2026-10-13T02:00:00Z"));
        PurchaseOrder receivedEarlier = order(shared, LocalDate.of(2026, 10, 13), true, Instant.parse("2026-10-10T02:00:00Z"));
        PurchaseOrder multiItem = orderWithItems(List.of(shared, extra), LocalDate.of(2026, 10, 25),
                Instant.parse("2026-10-14T02:00:00Z"));
        rename(later, "PO-SORT-B");
        rename(earlier, "PO-SORT-A");
        rename(multiItem, "PO-SORT-C");

        assertFirst("id,asc", null, shared, later.getId());
        assertFirst("poNumber,asc", null, shared, earlier.getId());
        assertFirst("expectedDeliveryDate,asc", null, shared, earlier.getId());
        assertFirst("orderedAt,asc", null, shared, earlier.getId());
        assertFirst("receivedAt,asc", "RECEIVED", shared, receivedEarlier.getId());
        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("materialId", shared.getId().toString())
                        .param("page", "0").param("size", "2").header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("materialId", shared.getId().toString())
                        .param("page", "1").param("size", "2").header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/v1/receiving/purchase-orders").param("materialId", shared.getId().toString())
                        .param("page", "2").param("size", "2").header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        assertThat(multiItem.getItems()).hasSize(2);
    }

    private void assertFirst(String sort, String requestedStatus, Material material, Long expectedId) throws Exception {
        var request = get("/api/v1/receiving/purchase-orders").param("sort", sort).param("materialId", material.getId().toString());
        if (requestedStatus != null) request.param("status", requestedStatus);
        mockMvc.perform(request.header("Authorization", bearer(warehouse))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(expectedId));
    }

    private void assertReceivingContract(JsonNode response) {
        assertThat(fieldNames(response)).containsExactlyInAnyOrder("id", "poNumber", "supplierId", "supplierCode",
                "supplierName", "status", "expectedDeliveryDate", "isOverdue", "items", "orderedAt", "receivedBy",
                "receivedByDisplayName", "receivedAt");
        assertThat(fieldNames(response.path("items").get(0))).containsExactlyInAnyOrder("id", "materialId", "materialCode",
                "materialName", "quantity", "unit");
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private PurchaseOrder order(Material material, LocalDate expectedDeliveryDate, boolean received) {
        return order(material, expectedDeliveryDate, received, clock.instant());
    }

    private PurchaseOrder order(Material material, LocalDate expectedDeliveryDate, boolean received, Instant orderedAt) {
        PurchaseOrder order = PurchaseOrder.create(supplier, expectedDeliveryDate, buyer, orderedAt);
        order.assignPendingPoNumber("P-" + suffix());
        order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.TEN, orderedAt));
        order.approve(admin, orderedAt);
        order.markOrdered(buyer, orderedAt);
        if (received) order.receive(warehouse, orderedAt);
        PurchaseOrder persisted = purchaseOrderRepository.saveAndFlush(order);
        persisted.assignDisplayPoNumber("PO-615-" + persisted.getId());
        return purchaseOrderRepository.saveAndFlush(persisted);
    }

    private PurchaseOrder persistedWithStatus(Material material, PurchaseOrderStatus status) {
        PurchaseOrder order = PurchaseOrder.create(supplier, LocalDate.now(clock).plusDays(1), buyer, clock.instant());
        order.assignPendingPoNumber("P-" + suffix());
        order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.TEN, clock.instant()));
        if (status == PurchaseOrderStatus.APPROVED) order.approve(admin, clock.instant());
        if (status == PurchaseOrderStatus.REJECTED) order.reject("Not required", admin, clock.instant());
        return purchaseOrderRepository.saveAndFlush(order);
    }

    private PurchaseOrder orderWithItems(List<Material> materials, LocalDate expectedDeliveryDate, Instant orderedAt) {
        PurchaseOrder order = PurchaseOrder.create(supplier, expectedDeliveryDate, buyer, orderedAt);
        order.assignPendingPoNumber("P-" + suffix());
        materials.forEach(material -> order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.TEN, orderedAt)));
        order.approve(admin, orderedAt);
        order.markOrdered(buyer, orderedAt);
        PurchaseOrder persisted = purchaseOrderRepository.saveAndFlush(order);
        persisted.assignDisplayPoNumber("PO-615-" + persisted.getId());
        return purchaseOrderRepository.saveAndFlush(persisted);
    }

    private void rename(PurchaseOrder order, String poNumber) {
        order.assignDisplayPoNumber(poNumber);
        purchaseOrderRepository.saveAndFlush(order);
    }

    private PurchaseOrder draft(Material material) {
        PurchaseOrder order = PurchaseOrder.create(supplier, LocalDate.now(clock).plusDays(1), buyer, clock.instant());
        order.assignPendingPoNumber("P-" + suffix());
        order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.TEN, clock.instant()));
        return purchaseOrderRepository.saveAndFlush(order);
    }

    private Material material(String name) {
        return materialRepository.saveAndFlush(Material.create("MAT-615-" + suffix(), name, BaseUnit.KG,
                BigDecimal.ZERO, clock.instant()));
    }

    private User user(UserRole role) {
        return userRepository.saveAndFlush(User.create("sp615-" + suffix(), "hash", role.name(), role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

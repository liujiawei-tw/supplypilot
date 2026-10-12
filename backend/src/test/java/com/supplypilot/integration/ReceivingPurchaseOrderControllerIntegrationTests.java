package com.supplypilot.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
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
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class ReceivingPurchaseOrderControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private UserRepository userRepository;

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

    private PurchaseOrder order(Material material, LocalDate expectedDeliveryDate, boolean received) {
        PurchaseOrder order = PurchaseOrder.create(supplier, expectedDeliveryDate, buyer, clock.instant());
        order.assignPendingPoNumber("P-" + suffix());
        order.addItem(PurchaseOrderItem.create(material, BigDecimal.ONE, BigDecimal.TEN, clock.instant()));
        order.approve(admin, clock.instant());
        order.markOrdered(buyer, clock.instant());
        if (received) order.receive(warehouse, clock.instant());
        PurchaseOrder persisted = purchaseOrderRepository.saveAndFlush(order);
        persisted.assignDisplayPoNumber("PO-615-" + persisted.getId());
        return purchaseOrderRepository.saveAndFlush(persisted);
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

package com.supplypilot.integration;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.domain.StockTransaction;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.inventory.repository.StockTransactionRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private StockTransactionRepository stockTransactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        jwtTokenService = new JwtTokenService(jwtProperties, clock);
    }

    @Test
    void listAppliesFiltersPaginationAndPublicResponseContract() throws Exception {
        Material first = materialWithInventory("MAT-INV-001", "Apple");
        materialWithInventory("MAT-INV-002", "Banana");
        materialWithInventory("RAW-INV-001", "Sugar");

        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN))
                        .param("materialId", first.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].materialId").value(first.getId()))
                .andExpect(jsonPath("$.content[0].materialCode").value(first.getCode()))
                .andExpect(jsonPath("$.content[0].materialName").value("Apple"))
                .andExpect(jsonPath("$.content[0].quantity").value(0))
                .andExpect(jsonPath("$.content[0].unit").value("KG"))
                .andExpect(jsonPath("$.content[0].updatedAt").exists())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.totalPages").value(1));

        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN))
                        .param("materialCode", "MAT-INV").param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void validatesPaginationAndReturnsEmptyPageForUnknownListMaterial() throws Exception {
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN)).param("size", "100"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN)).param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN)).param("page", "-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN)).param("size", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN)).param("materialId", "999999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void detailDistinguishesMissingMaterialAndInventory() throws Exception {
        Material material = materialWithInventory("MAT-DETAIL", "Detail material");
        mockMvc.perform(get("/api/v1/inventory/{materialId}", material.getId()).header("Authorization", bearer(UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.materialId").value(material.getId()));
        mockMvc.perform(get("/api/v1/inventory/{materialId}", 999999999L).header("Authorization", bearer(UserRole.ADMIN)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MATERIAL_NOT_FOUND"));

        Material missingInventory = material("MAT-MISSING-INV", "Missing inventory");
        jdbcTemplate.update("DELETE FROM inventory WHERE material_id = ?", missingInventory.getId());
        mockMvc.perform(get("/api/v1/inventory/{materialId}", missingInventory.getId()).header("Authorization", bearer(UserRole.ADMIN)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
    }

    @Test
    void requiresAuthenticationAndRestrictsAccessToInventoryRoles() throws Exception {
        mockMvc.perform(get("/api/v1/inventory")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        for (UserRole role : new UserRole[] {UserRole.PRODUCTION_PLANNER, UserRole.PROCUREMENT_STAFF, UserRole.WAREHOUSE_STAFF, UserRole.ADMIN}) {
            mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(role))).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.PROCUREMENT_MANAGER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void manualAdjustmentUpdatesInventoryAndPersistsOneAuditRecord() throws Exception {
        Material material = materialWithQuantity("MAT-ADJUST", "Adjust material", "100");
        User actor = user(UserRole.ADMIN);
        long versionBefore = inventoryRepository.findByMaterialId(material.getId()).orElseThrow().getVersion();

        mockMvc.perform(post("/api/v1/inventory/{materialId}/adjustments", material.getId())
                        .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":-30,\"reason\":\"Damaged inventory\"}"))
                .andExpect(status().isOk());

        Inventory persistedInventory = inventoryRepository.findByMaterialId(material.getId()).orElseThrow();
        assertThat(persistedInventory.getQuantity()).isEqualByComparingTo("70.0000");
        assertThat(persistedInventory.getVersion()).isEqualTo(versionBefore + 1);
        assertThat(transactionsFor(material)).singleElement().satisfies(transaction -> {
            assertThat(transaction.getQuantity()).isEqualByComparingTo("-30.0000");
            assertThat(transaction.getReason()).isEqualTo("Damaged inventory");
            assertThat(transaction.getCreatedBy().getId()).isEqualTo(actor.getId());
            assertThat(transaction.getCreatedAt()).isEqualTo(clock.instant());
            assertThat(transaction.getPurchaseOrderId()).isNull();
        });
    }

    @Test
    void manualAdjustmentSupportsStockIncreaseAndRejectsNegativeStockWithoutAudit() throws Exception {
        Material material = materialWithQuantity("MAT-ADJUST-N", "Adjust material", "100");
        User actor = user(UserRole.WAREHOUSE_STAFF);

        mockMvc.perform(post("/api/v1/inventory/{materialId}/adjustments", material.getId())
                        .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":30,\"reason\":\"Count correction\"}"))
                .andExpect(status().isOk());
        assertThat(inventoryRepository.findByMaterialId(material.getId()).orElseThrow().getQuantity())
                .isEqualByComparingTo("130.0000");

        mockMvc.perform(post("/api/v1/inventory/{materialId}/adjustments", material.getId())
                        .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":-131,\"reason\":\"Damaged inventory\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NEGATIVE_QUANTITY"));
        assertThat(inventoryRepository.findByMaterialId(material.getId()).orElseThrow().getQuantity())
                .isEqualByComparingTo("130.0000");
        assertThat(transactionsFor(material)).hasSize(1);
    }

    @Test
    void manualAdjustmentAcceptsExactDecimalStringWithoutLosingBigDecimalPrecision() throws Exception {
        Material material = materialWithInventory("MAT-ADJUST-PRECISE", "Precise adjustment material");
        User actor = user(UserRole.ADMIN);

        mockMvc.perform(post("/api/v1/inventory/{materialId}/adjustments", material.getId())
                        .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"999999999999999.1234\",\"reason\":\"Precision contract\"}"))
                .andExpect(status().isOk());

        assertThat(inventoryRepository.findByMaterialId(material.getId()).orElseThrow().getQuantity())
                .isEqualByComparingTo("999999999999999.1234");
        assertThat(transactionsFor(material)).singleElement().satisfies(transaction ->
                assertThat(transaction.getQuantity()).isEqualByComparingTo("999999999999999.1234"));
    }

    @Test
    void manualAdjustmentValidatesRequestAndDistinguishesMissingMaterialAndInventory() throws Exception {
        User actor = user(UserRole.ADMIN);
        String endpoint = "/api/v1/inventory/999999999/adjustments";
        for (String body : new String[] {
                "{\"quantity\":0,\"reason\":\"Reason\"}",
                "{\"quantity\":null,\"reason\":\"Reason\"}",
                "{\"quantity\":1,\"reason\":null}",
                "{\"quantity\":1,\"reason\":\"\"}",
                "{\"quantity\":1,\"reason\":\"   \"}",
                "{\"quantity\":1.00001,\"reason\":\"Reason\"}",
                "{\"quantity\":1000000000000000,\"reason\":\"Reason\"}"
        }) {
            mockMvc.perform(post(endpoint).header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        mockMvc.perform(post(endpoint).header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"reason\":\"Reason\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MATERIAL_NOT_FOUND"));

        Material material = material("MAT-ADJUST-MISSING", "Missing inventory");
        jdbcTemplate.update("DELETE FROM inventory WHERE material_id = ?", material.getId());
        mockMvc.perform(post("/api/v1/inventory/{materialId}/adjustments", material.getId()).header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1,\"reason\":\"Reason\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
    }

    @Test
    void manualAdjustmentEnforcesRolesAndRejectsForgedAuditActor() throws Exception {
        Material material = materialWithQuantity("MAT-ADJUST-SEC", "Secure material", "100");

        mockMvc.perform(post("/api/v1/inventory/{materialId}/adjustments", material.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1,\"reason\":\"Reason\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        for (UserRole role : new UserRole[] {UserRole.PRODUCTION_PLANNER, UserRole.PROCUREMENT_STAFF, UserRole.PROCUREMENT_MANAGER}) {
            User actor = user(role);
            mockMvc.perform(post("/api/v1/inventory/{materialId}/adjustments", material.getId()).header("Authorization", bearer(actor))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1,\"reason\":\"Reason\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        User actor = user(UserRole.ADMIN);
        mockMvc.perform(post("/api/v1/inventory/{materialId}/adjustments", material.getId()).header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"reason\":\"Reason\",\"createdBy\":999999}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(transactionsFor(material)).isEmpty();
    }

    @Test
    void rollsBackInventoryWhenTheMySqlStockTransactionInsertFails() throws Exception {
        Material material = materialWithQuantity("MAT-ADJUST-ROLLBACK", "Rollback material", "100");
        User actor = user(UserRole.ADMIN);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_sp401_stock_transaction_failure");
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_sp401_stock_transaction_failure
                BEFORE INSERT ON stock_transactions FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced stock transaction failure'
                """);
        try {
            mockMvc.perform(post("/api/v1/inventory/{materialId}/adjustments", material.getId()).header("Authorization", bearer(actor))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":-30,\"reason\":\"Forced audit failure\"}"))
                    .andExpect(status().isInternalServerError());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_sp401_stock_transaction_failure");
        }

        assertThat(inventoryRepository.findByMaterialId(material.getId()).orElseThrow().getQuantity())
                .isEqualByComparingTo("100.0000");
        assertThat(transactionsFor(material)).isEmpty();
    }

    private Material materialWithInventory(String code, String name) {
        Material material = material(code, name);
        inventoryRepository.saveAndFlush(Inventory.create(material, clock.instant()));
        return material;
    }

    private Material materialWithQuantity(String code, String name, String quantity) {
        Material material = materialWithInventory(code, name);
        Inventory inventory = inventoryRepository.findByMaterialId(material.getId()).orElseThrow();
        inventory.adjust(new BigDecimal(quantity), clock.instant());
        inventoryRepository.saveAndFlush(inventory);
        return material;
    }

    private Material material(String code, String name) {
        return materialRepository.saveAndFlush(Material.create(code + "-" + UUID.randomUUID().toString().substring(0, 8), name, BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
    }

    private String bearer(UserRole role) {
        User principal = userRepository.saveAndFlush(User.create("inventory-auth-" + UUID.randomUUID(), "hash",
                role.name(), role, clock.instant()));
        return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(principal.getId(), principal.getUsername(), principal.getRole()));
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private User user(UserRole role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create("adjust-" + suffix, "hash", "Adjustment user", role, clock.instant()));
    }

    private java.util.List<StockTransaction> transactionsFor(Material material) {
        return stockTransactionRepository.findAll().stream()
                .filter(transaction -> transaction.getMaterial().getId().equals(material.getId()))
                .toList();
    }
}

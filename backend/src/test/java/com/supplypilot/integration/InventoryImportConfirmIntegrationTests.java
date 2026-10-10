package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.importing.domain.InventoryImportBatch;
import com.supplypilot.inventory.importing.domain.InventoryImportStatus;
import com.supplypilot.inventory.importing.repository.InventoryImportBatchRepository;
import com.supplypilot.inventory.importing.repository.InventoryImportItemRepository;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.inventory.repository.StockTransactionRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class InventoryImportConfirmIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private StockTransactionRepository stockTransactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InventoryImportBatchRepository batchRepository;
    @Autowired private InventoryImportItemRepository itemRepository;
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
    void confirmsPersistedSnapshotsWithoutCreatingStockTransactions() throws Exception {
        Material first = materialWithQuantity("MAT-CONFIRM-A", "100");
        Material second = materialWithQuantity("MAT-CONFIRM-B", "50");
        Material untouched = materialWithQuantity("MAT-CONFIRM-C", "30");
        User actor = user(UserRole.ADMIN);
        long firstVersion = inventory(first).getVersion();
        long secondVersion = inventory(second).getVersion();
        long untouchedVersion = inventory(untouched).getVersion();
        long stockTransactionCountBefore = stockTransactionRepository.count();
        InventoryImportBatch batch = validate(actor, rows(first, "200", second, "80"));

        mockMvc.perform(post(confirmPath(batch)).header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":999,\"materialId\":999,\"createdBy\":999}"))
                .andExpect(status().isOk());

        assertThat(inventory(first).getQuantity()).isEqualByComparingTo("200.0000");
        assertThat(inventory(second).getQuantity()).isEqualByComparingTo("80.0000");
        assertThat(inventory(untouched).getQuantity()).isEqualByComparingTo("30.0000");
        assertThat(inventory(first).getVersion()).isEqualTo(firstVersion + 1);
        assertThat(inventory(second).getVersion()).isEqualTo(secondVersion + 1);
        assertThat(inventory(untouched).getVersion()).isEqualTo(untouchedVersion);
        assertThat(batchRepository.findById(batch.getId()).orElseThrow().getStatus()).isEqualTo(InventoryImportStatus.IMPORTED);
        assertThat(stockTransactionRepository.count()).isEqualTo(stockTransactionCountBefore);
    }

    @Test
    void acceptsZeroSnapshotAndRejectsDuplicateConfirm() throws Exception {
        Material material = materialWithQuantity("MAT-CONFIRM-ZERO", "100");
        User actor = user(UserRole.WAREHOUSE_STAFF);
        InventoryImportBatch batch = validate(actor, rows(material, "0"));

        mockMvc.perform(post(confirmPath(batch)).header("Authorization", bearer(actor))).andExpect(status().isOk());
        assertThat(inventory(material).getQuantity()).isEqualByComparingTo("0.0000");
        mockMvc.perform(post(confirmPath(batch)).header("Authorization", bearer(actor)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVENTORY_IMPORT_ALREADY_CONFIRMED"));
    }

    @Test
    void rollsBackEveryInventoryChangeWhenLaterInventoryUpdateFails() throws Exception {
        Material first = materialWithQuantity("MAT-CONFIRM-ROLLBACK-A", "100");
        Material second = materialWithQuantity("MAT-CONFIRM-ROLLBACK-B", "50");
        User actor = user(UserRole.ADMIN);
        InventoryImportBatch batch = validate(actor, rows(first, "200", second, "80"));
        long firstVersion = inventory(first).getVersion();
        long secondVersion = inventory(second).getVersion();
        long stockTransactionCountBefore = stockTransactionRepository.count();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_sp411_inventory_failure");
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_sp411_inventory_failure BEFORE UPDATE ON inventory FOR EACH ROW
                BEGIN
                    IF NEW.material_id = %d THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced later inventory update failure';
                    END IF;
                END
                """.formatted(second.getId()));
        try {
            mockMvc.perform(post(confirmPath(batch)).header("Authorization", bearer(actor)))
                    .andExpect(status().isInternalServerError());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_sp411_inventory_failure");
        }

        assertThat(inventory(first).getQuantity()).isEqualByComparingTo("100.0000");
        assertThat(inventory(second).getQuantity()).isEqualByComparingTo("50.0000");
        assertThat(inventory(first).getVersion()).isEqualTo(firstVersion);
        assertThat(inventory(second).getVersion()).isEqualTo(secondVersion);
        assertThat(batchRepository.findById(batch.getId()).orElseThrow().getStatus()).isEqualTo(InventoryImportStatus.VALIDATED);
        assertThat(stockTransactionRepository.count()).isEqualTo(stockTransactionCountBefore);
    }

    @Test
    void rejectsInvalidLifecycleAndTamperedPersistedPreview() throws Exception {
        User actor = user(UserRole.ADMIN);
        Material first = materialWithQuantity("MAT-CONFIRM-INVALID-A", "10");
        Material second = materialWithQuantity("MAT-CONFIRM-INVALID-B", "20");
        InventoryImportBatch invalidBatch = validate(actor, "material_code,quantity,unit\nMISSING,1,KG\n");
        mockMvc.perform(post(confirmPath(invalidBatch)).header("Authorization", bearer(actor)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVENTORY_IMPORT_NOT_VALIDATED"));

        for (InventoryImportStatus status : List.of(InventoryImportStatus.UPLOADED, InventoryImportStatus.IMPORT_FAILED)) {
            InventoryImportBatch batch = validate(actor, rows(first, "30"));
            jdbcTemplate.update("UPDATE inventory_import_batches SET status = ? WHERE id = ?", status.name(), batch.getId());
            mockMvc.perform(post(confirmPath(batch)).header("Authorization", bearer(actor)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVENTORY_IMPORT_NOT_VALIDATED"));
        }

        InventoryImportBatch missingItems = validate(actor, rows(first, "30"));
        jdbcTemplate.update("DELETE FROM inventory_import_items WHERE batch_id = ?", missingItems.getId());
        mockMvc.perform(post(confirmPath(missingItems)).header("Authorization", bearer(actor)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVENTORY_IMPORT_NOT_VALIDATED"));

        InventoryImportBatch incomplete = validate(actor, rows(first, "30"));
        jdbcTemplate.update("UPDATE inventory_import_items SET unit = NULL WHERE batch_id = ?", incomplete.getId());
        mockMvc.perform(post(confirmPath(incomplete)).header("Authorization", bearer(actor)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVENTORY_IMPORT_NOT_VALIDATED"));

        InventoryImportBatch duplicate = validate(actor, rows(first, "30", second, "40"));
        List<Long> itemIds = itemRepository.findByBatchIdOrderByRowNumber(duplicate.getId()).stream().map(item -> item.getId()).toList();
        jdbcTemplate.update("UPDATE inventory_import_items SET material_id = ? WHERE id = ?", first.getId(), itemIds.get(1));
        mockMvc.perform(post(confirmPath(duplicate)).header("Authorization", bearer(actor)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVENTORY_IMPORT_NOT_VALIDATED"));

        mockMvc.perform(post("/api/v1/inventory-imports/{id}/confirm", 999999999L).header("Authorization", bearer(actor)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVENTORY_IMPORT_BATCH_NOT_FOUND"));
    }

    @Test
    void enforcesConfirmRoles() throws Exception {
        Material material = materialWithQuantity("MAT-CONFIRM-SECURITY", "10");
        User admin = user(UserRole.ADMIN);
        InventoryImportBatch batch = validate(admin, rows(material, "20"));
        mockMvc.perform(post(confirmPath(batch))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        for (UserRole role : List.of(UserRole.PRODUCTION_PLANNER, UserRole.PROCUREMENT_STAFF, UserRole.PROCUREMENT_MANAGER)) {
            mockMvc.perform(post(confirmPath(batch)).header("Authorization", bearer(user(role))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        mockMvc.perform(post(confirmPath(batch)).header("Authorization", bearer(admin))).andExpect(status().isOk());
    }

    @Test
    void serializesConcurrentConfirmRequestsForTheSameBatch() throws Exception {
        Material material = materialWithQuantity("MAT-CONFIRM-CONCURRENT", "100");
        User actor = user(UserRole.ADMIN);
        InventoryImportBatch batch = validate(actor, rows(material, "200"));
        long versionBefore = inventory(material).getVersion();
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> { startTogether.await(); return confirmResult(batch, actor); });
            Future<MvcResult> second = executor.submit(() -> { startTogether.await(); return confirmResult(batch, actor); });
            List<MvcResult> results = List.of(first.get(), second.get());
            assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(200, 409);
            assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow()
                    .getResponse().getContentAsString()).contains("INVENTORY_IMPORT_ALREADY_CONFIRMED");
        } finally {
            executor.shutdownNow();
        }
        assertThat(batchRepository.findById(batch.getId()).orElseThrow().getStatus()).isEqualTo(InventoryImportStatus.IMPORTED);
        assertThat(inventory(material).getQuantity()).isEqualByComparingTo("200.0000");
        assertThat(inventory(material).getVersion()).isEqualTo(versionBefore + 1);
    }

    private MvcResult confirmResult(InventoryImportBatch batch, User actor) throws Exception {
        return mockMvc.perform(post(confirmPath(batch)).header("Authorization", bearer(actor))).andReturn();
    }

    private InventoryImportBatch validate(User actor, String csv) throws Exception {
        mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(csv(csv)).header("Authorization", bearer(actor)))
                .andExpect(status().isOk());
        return batchRepository.findAll().stream().max(Comparator.comparingLong(InventoryImportBatch::getId)).orElseThrow();
    }

    private Material materialWithQuantity(String code, String quantity) {
        Material material = materialRepository.saveAndFlush(Material.create(code + "-" + UUID.randomUUID().toString().substring(0, 8),
                code, BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        Inventory inventory = inventoryRepository.saveAndFlush(Inventory.create(material, clock.instant()));
        inventory.adjust(new BigDecimal(quantity), clock.instant());
        inventoryRepository.saveAndFlush(inventory);
        return material;
    }

    private Inventory inventory(Material material) { return inventoryRepository.findByMaterialId(material.getId()).orElseThrow(); }
    private User user(UserRole role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create("confirm-" + suffix, "hash", "Confirm user", role, clock.instant()));
    }
    private String bearer(User user) { return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole())); }
    private String confirmPath(InventoryImportBatch batch) { return "/api/v1/inventory-imports/" + batch.getId() + "/confirm"; }
    private MockMultipartFile csv(String contents) { return new MockMultipartFile("file", "inventory.csv", MediaType.TEXT_PLAIN_VALUE, contents.getBytes()); }
    private String rows(Material first, String firstQuantity) { return rows(first, firstQuantity, null, null); }
    private String rows(Material first, String firstQuantity, Material second, String secondQuantity) {
        String result = "material_code,quantity,unit\n" + first.getCode() + "," + firstQuantity + ",KG\n";
        return second == null ? result : result + second.getCode() + "," + secondQuantity + ",KG\n";
    }
}

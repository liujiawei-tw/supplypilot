package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import com.supplypilot.inventory.domain.Inventory;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class InventoryImportControllerIntegrationTests extends AbstractIntegrationTest {
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
    void validatesAndPersistsPreviewWithoutChangingInventoryOrStockTransactions() throws Exception {
        Material material = material("MAT-CSV-VALID");
        Inventory inventory = inventoryRepository.saveAndFlush(Inventory.create(material, clock.instant()));
        User actor = user(UserRole.WAREHOUSE_STAFF);
        long stockTransactionCountBefore = stockTransactionRepository.count();

        mockMvc.perform(multipart("/api/v1/inventory-imports/validate")
                        .file(csv("material_code,quantity,unit\n  " + material.getCode().toLowerCase() + "  ,0,KG\n"))
                        .header("Authorization", bearer(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.validRows").value(1))
                .andExpect(jsonPath("$.errorRows").value(0))
                .andExpect(jsonPath("$.errors").isEmpty());

        var batch = batchRepository.findAll().stream().max(java.util.Comparator.comparingLong(b -> b.getId())).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(InventoryImportStatus.VALIDATED);
        assertThat(batch.getUploadedBy().getId()).isEqualTo(actor.getId());
        assertThat(itemRepository.findByBatchIdOrderByRowNumber(batch.getId())).singleElement().satisfies(item -> {
            assertThat(item.isValid()).isTrue();
            assertThat(item.getMaterial().getId()).isEqualTo(material.getId());
            assertThat(item.getQuantity()).isEqualByComparingTo("0.0000");
        });
        Inventory after = inventoryRepository.findByMaterialId(material.getId()).orElseThrow();
        assertThat(after.getQuantity()).isEqualByComparingTo(inventory.getQuantity());
        assertThat(after.getVersion()).isEqualTo(inventory.getVersion());
        assertThat(stockTransactionRepository.count()).isEqualTo(stockTransactionCountBefore);
    }

    @Test
    void persistsEveryInvalidRowAndReturnsItsPreviewErrors() throws Exception {
        Material invalidQuantityMaterial = material("MAT-CSV-INVALID");
        Material unitMismatchMaterial = material("MAT-CSV-UNIT");
        User actor = user(UserRole.ADMIN);
        String body = """
                material_code,quantity,unit
                %s,-1,KG
                MAT-MISSING,1,KG
                %s,1,PCS
                %s,2,KG
                """.formatted(invalidQuantityMaterial.getCode(), unitMismatchMaterial.getCode(), invalidQuantityMaterial.getCode());

        mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(csv(body))
                        .header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.totalRows").value(4)).andExpect(jsonPath("$.validRows").value(0))
                .andExpect(jsonPath("$.errorRows").value(4))
                .andExpect(jsonPath("$.errors[0].code").value("NEGATIVE_QUANTITY"))
                .andExpect(jsonPath("$.errors[1].code").value("MATERIAL_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[2].code").value("UNIT_MISMATCH"))
                .andExpect(jsonPath("$.errors[3].code").value("DUPLICATE_MATERIAL"));

        var batch = batchRepository.findAll().stream().max(java.util.Comparator.comparingLong(b -> b.getId())).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(InventoryImportStatus.VALIDATION_FAILED);
        assertThat(itemRepository.findByBatchIdOrderByRowNumber(batch.getId())).hasSize(4)
                .extracting(item -> item.getErrorCode()).containsExactly(
                        "NEGATIVE_QUANTITY", "MATERIAL_NOT_FOUND", "UNIT_MISMATCH", "DUPLICATE_MATERIAL");
    }

    @Test
    void reportsAndPersistsPhysicalLineNumbersAfterAQuotedNewline() throws Exception {
        Material material = material("MAT-CSV-MULTILINE");
        User actor = user(UserRole.ADMIN);
        String body = "material_code,quantity,unit\n"
                + material.getCode() + ",\"1\n\",KG\n"
                + "MAT-MISSING,1,KG\n";

        mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(csv(body))
                        .header("Authorization", bearer(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].row").value(4))
                .andExpect(jsonPath("$.errors[0].code").value("MATERIAL_NOT_FOUND"));

        var batch = batchRepository.findAll().stream().max(java.util.Comparator.comparingLong(b -> b.getId())).orElseThrow();
        assertThat(itemRepository.findByBatchIdOrderByRowNumber(batch.getId()))
                .extracting(item -> item.getRowNumber()).containsExactly(2, 4);
    }

    @Test
    void enforcesRolesAndFileLevelValidation() throws Exception {
        Material material = material("MAT-CSV-SECURITY");
        User warehouse = user(UserRole.WAREHOUSE_STAFF);
        mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(csv(rowFor(material)))
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk());

        for (UserRole role : new UserRole[] {UserRole.PRODUCTION_PLANNER, UserRole.PROCUREMENT_STAFF, UserRole.PROCUREMENT_MANAGER}) {
            mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(csv(rowFor(material)))
                            .header("Authorization", bearer(user(role))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(csv(rowFor(material))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(multipart("/api/v1/inventory-imports/validate").header("Authorization", bearer(warehouse)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        MockMultipartFile json = new MockMultipartFile("file", "inventory.json", MediaType.APPLICATION_JSON_VALUE, "{}".getBytes());
        mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(json).header("Authorization", bearer(warehouse)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILE_FORMAT"));
        MockMultipartFile oversized = new MockMultipartFile("file", "inventory.csv", MediaType.TEXT_PLAIN_VALUE,
                new byte[5 * 1024 * 1024 + 1]);
        mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(oversized).header("Authorization", bearer(warehouse)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void rollsBackBatchWhenAnItemInsertFails() throws Exception {
        Material material = material("MAT-CSV-ROLLBACK");
        User actor = user(UserRole.ADMIN);
        long batchCountBefore = batchRepository.count();
        long itemCountBefore = itemRepository.count();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_sp410_item_failure");
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_sp410_item_failure
                BEFORE INSERT ON inventory_import_items FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced item failure'
                """);
        try {
            mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(csv(rowFor(material)))
                            .header("Authorization", bearer(actor)))
                    .andExpect(status().isInternalServerError());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_sp410_item_failure");
        }
        assertThat(batchRepository.count()).isEqualTo(batchCountBefore);
        assertThat(itemRepository.count()).isEqualTo(itemCountBefore);
    }

    private Material material(String code) {
        return materialRepository.saveAndFlush(Material.create(code + "-" + UUID.randomUUID().toString().substring(0, 8),
                code, BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
    }

    private User user(UserRole role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create("import-" + suffix, "hash", "Import user", role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private MockMultipartFile csv(String contents) {
        return new MockMultipartFile("file", "../../inventory.csv", MediaType.TEXT_PLAIN_VALUE, contents.getBytes());
    }

    private String rowFor(Material material) {
        return "material_code,quantity,unit\n" + material.getCode() + ",1,KG\n";
    }
}

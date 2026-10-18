package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.TestcontainersConfiguration;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.rag.DocumentTextExtractor;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.testsupport.FixedClockConfiguration;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "supplypilot.demo.allowed-database=supplypilot_demo_test",
        "supplypilot.demo.dataset-version=sp-905-zh-tw-v1",
        "supplypilot.demo.samples-path=../samples/documents",
        "supplypilot.document-storage.path=${java.io.tmpdir}/supplypilot-demo-seed-test-documents",
        "supplypilot.jwt.secret=dGVzdC1zZWNyZXQtZm9yLWRlbW8tc2VlZC10ZXN0cy1hdC1sZWFzdC0yNTYtYml0cw==",
        "supplypilot.frontend-origin=http://localhost:5173"
})
@ActiveProfiles("demo")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@AutoConfigureMockMvc
class DemoSeedSmokeIntegrationTests {
    private static final String PASSWORD = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 5);
    private static final String ROTATED_PASSWORD = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 5);
    private static final Path DYNAMIC_PASSWORD_FILE = Path.of(System.getProperty("java.io.tmpdir"),
            "supplypilot-demo-auth-" + UUID.randomUUID() + ".password");

    static {
        writeDynamicPassword(PASSWORD);
    }

    @DynamicPropertySource
    static void demoAccountPassword(DynamicPropertyRegistry registry) {
        registry.add("supplypilot.demo.account-password", () -> PASSWORD);
        registry.add("supplypilot.demo.password-file", () -> DYNAMIC_PASSWORD_FILE.toString());
    }

    @BeforeEach
    void resetDynamicPasswordFile() { writeDynamicPassword(PASSWORD); }

    @AfterAll
    static void removeDynamicPasswordFile() throws Exception { Files.deleteIfExists(DYNAMIC_PASSWORD_FILE); }

    @Autowired private UserRepository users;
    @Autowired private MaterialRepository materials;
    @Autowired private InventoryRepository inventories;
    @Autowired private SupplierRepository suppliers;
    @Autowired private SupplierMaterialRepository supplierMaterials;
    @Autowired private PurchaseOrderRepository purchaseOrders;
    @Autowired private DocumentMetadataRepository documents;
    @Autowired private DocumentChunkRepository documentChunks;
    @Autowired private DocumentTextExtractor documentTextExtractor;
    @Autowired private DemoDataCoordinator coordinator;
    @Autowired private DemoProperties demoProperties;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void allFiveBaselineAccountsUseTheCurrentFilePasswordWithoutRestartAndRejectTheOldPassword() throws Exception {
        Map<String, String> expectedRoles = Map.of(
                "admin", "ADMIN", "manager", "PROCUREMENT_MANAGER", "buyer", "PROCUREMENT_STAFF",
                "warehouse", "WAREHOUSE_STAFF", "planner", "PRODUCTION_PLANNER");
        for (var entry : expectedRoles.entrySet()) {
            assertLogin(entry.getKey(), PASSWORD, 200, entry.getValue());
        }

        writeDynamicPassword(ROTATED_PASSWORD);
        for (var entry : expectedRoles.entrySet()) {
            assertLogin(entry.getKey(), PASSWORD, 401, null);
            assertLogin(entry.getKey(), ROTATED_PASSWORD, 200, entry.getValue());
        }
    }

    @Test
    void inactiveBaselineAccountIsRejectedByHttpLogin() throws Exception {
        var admin = users.findByUsername("admin").orElseThrow();
        admin.inactivate(java.time.Instant.now());
        users.saveAndFlush(admin);
        assertLogin("admin", PASSWORD, 401, null);
        admin.activate(java.time.Instant.now());
        users.saveAndFlush(admin);
    }

    @Test
    void nonBaselineAccountStillUsesDatabasePassword() throws Exception {
        String username = "demo_custom_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User user = User.create(username, passwordEncoder.encode("Database123"), "Custom Demo User",
                UserRole.PROCUREMENT_STAFF, java.time.Instant.now());
        users.saveAndFlush(user);
        try {
            writeDynamicPassword(ROTATED_PASSWORD);
            assertLogin(username, "Database123", 200, "PROCUREMENT_STAFF");
            assertLogin(username, ROTATED_PASSWORD, 401, null);
        } finally {
            users.deleteById(user.getId());
        }
    }

    @Test
    void seedsCompleteDeterministicBaselineAndCanRunAgainWithoutDuplicates() {
        assertThat(users.count()).isEqualTo(5);
        assertThat(materials.count()).isEqualTo(12);
        assertThat(suppliers.count()).isEqualTo(6);
        assertThat(supplierMaterials.count()).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demands", Long.class)).isEqualTo(12);
        assertThat(purchaseOrders.count()).isEqualTo(10);
        assertThat(documents.count()).isEqualTo(5);
        assertThat(documents.findAll()).allMatch(document -> document.getStatus() == DocumentStatus.READY);
        assertThat(documents.findAll()).extracting(document -> document.getTitle()).containsExactlyInAnyOrder(
                "SUP-001 交付與責任協議", "SUP-002 虛構報價條件", "SupplyPilot 虛構展示採購作業程序",
                "MAT-001 阿薩姆紅茶萃取物規格", "虛構展示原料品質要求");
        assertThat(documents.findAll()).extracting(document -> document.getDocumentType()).containsExactlyInAnyOrder(
                DocumentType.SUPPLIER_CONTRACT, DocumentType.QUOTATION, DocumentType.PROCUREMENT_SOP,
                DocumentType.PRODUCT_SPECIFICATION, DocumentType.QUALITY_REQUIREMENT);
        assertThat(documents.findAll()).allSatisfy(document -> {
            assertThat(document.getVersion()).isEqualTo("1.0");
            assertThat(document.getSemanticReviewStatus()).isNull();
            assertThat(Path.of(System.getProperty("java.io.tmpdir"), "supplypilot-demo-seed-test-documents",
                    document.getStoragePath())).isRegularFile();
        });
        assertThat(documents.findAll()).noneMatch(document -> document.getTitle().contains("SUP-005 季節性"));
        assertThat(jdbc.queryForObject("SELECT dataset_version FROM demo_dataset_state WHERE dataset_key = 'SP-901'", String.class))
                .isEqualTo("sp-905-zh-tw-v1");
        assertThat(documents.findAll()).allSatisfy(document -> {
            Path source = Path.of(System.getProperty("java.io.tmpdir"), "supplypilot-demo-seed-test-documents",
                    document.getStoragePath());
            String text = documentTextExtractor.extract(document.getStoragePath());
            assertThat(text).contains("虛構展示文件", "標題：" + document.getTitle());
            var chunks = documentChunks.findByDocumentIdOrderByChunkIndex(document.getId());
            assertThat(chunks).isNotEmpty();
            assertThat(chunks).allSatisfy(chunk -> {
                assertThat(chunk.getContent()).isEqualTo(text.substring(chunk.getStartOffset(), chunk.getEndOffset()).trim());
                assertThat(chunk.getEmbedding()).isNotBlank();
            });
        });

        Map<PurchaseOrderStatus, Long> expected = Map.of(
                PurchaseOrderStatus.DRAFT, 2L,
                PurchaseOrderStatus.APPROVED, 2L,
                PurchaseOrderStatus.ORDERED, 3L,
                PurchaseOrderStatus.RECEIVED, 2L,
                PurchaseOrderStatus.REJECTED, 1L);
        for (var entry : expected.entrySet()) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE status = ?",
                    Long.class, entry.getKey().name());
            assertThat(count).isEqualTo(entry.getValue());
        }

        var admin = users.findByUsername("admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
        Map<String, UserRole> demoRoles = Map.of(
                "planner", UserRole.PRODUCTION_PLANNER,
                "buyer", UserRole.PROCUREMENT_STAFF,
                "manager", UserRole.PROCUREMENT_MANAGER,
                "warehouse", UserRole.WAREHOUSE_STAFF,
                "admin", UserRole.ADMIN);
        demoRoles.forEach((username, role) -> {
            var seededUser = users.findByUsername(username).orElseThrow();
            assertThat(seededUser.getRole()).isEqualTo(role);
            assertThat(passwordEncoder.matches(PASSWORD, seededUser.getPasswordHash())).isTrue();
        });
        demoRoles.keySet().forEach(username -> {
            try {
                assertThat(login(username)).isNotBlank();
            } catch (Exception exception) {
                throw new AssertionError("Demo user login should succeed for " + username, exception);
            }
        });
        assertThat(jdbc.queryForObject(
                "SELECT status FROM demo_dataset_state WHERE dataset_key = 'SP-901'", String.class))
                .isEqualTo("COMPLETE");

        coordinator.initialize();
        assertThat(users.count()).isEqualTo(5);
        assertThat(purchaseOrders.count()).isEqualTo(10);
        assertThat(documents.count()).isEqualTo(5);

        demoProperties.setDatasetVersion("sp-901-v1");
        assertThatThrownBy(coordinator::initialize).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different version");
        assertThat(documents.count()).isEqualTo(5);
        demoProperties.setDatasetVersion("sp-905-zh-tw-v1");
    }

    @Test
    void standardScenarioBUsesRealRecommendationApiAndReturnsSevenHundredFiftyKg() throws Exception {
        Long materialId = materials.findByCode("MAT-001").orElseThrow().getId();
        LocalDate baseline = jdbc.queryForObject(
                "SELECT baseline_date FROM demo_dataset_state WHERE dataset_key = 'SP-901'", LocalDate.class);
        String token = login("buyer");
        String request = """
                {"materialId":%d,"periodStart":"%s","periodEnd":"%s"}
                """.formatted(materialId, baseline, baseline.plusDays(30));

        mockMvc.perform(post("/api/v1/recommendations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.calculation.expectedDemand").value(1000))
                .andExpect(jsonPath("$.calculation.safetyStock").value(200))
                .andExpect(jsonPath("$.calculation.currentInventory").value(300))
                .andExpect(jsonPath("$.calculation.incomingInventory").value(150))
                .andExpect(jsonPath("$.calculation.netRequirement").value(750));
    }

    @Test
    @Transactional
    void refusesPartialStateInsteadOfOverwritingExistingBusinessData() {
        jdbc.update("DELETE FROM demo_dataset_state WHERE dataset_key = 'SP-901'");
        assertThatThrownBy(coordinator::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-empty database");
        assertThat(users.count()).isEqualTo(5);
        assertThat(materials.findByCode("MAT-001")).isPresent();
    }

    @Test
    @Transactional
    void runsRecommendationDraftApproveOrderReceiveThroughRealApisAndPreservesAudit() throws Exception {
        var material = materials.findByCode("MAT-001").orElseThrow();
        LocalDate baseline = jdbc.queryForObject(
                "SELECT baseline_date FROM demo_dataset_state WHERE dataset_key = 'SP-901'", LocalDate.class);
        String buyer = login("buyer");
        String manager = login("manager");
        String warehouse = login("warehouse");

        JsonNode recommendation = responseJson(post("/api/v1/recommendations")
                .header("Authorization", bearer(buyer)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "materialId", material.getId(), "periodStart", baseline,
                        "periodEnd", baseline.plusDays(30)))), 201);
        long recommendationId = recommendation.get("id").asLong();
        long supplierId = recommendation.get("recommendation").get("supplierId").asLong();
        BigDecimal quantity = recommendation.get("recommendation").get("quantity").decimalValue();

        String suppliersJson = mockMvc.perform(get("/api/v1/recommendations/{id}/suppliers", recommendationId)
                        .header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode selected = objectMapper.readTree(suppliersJson).valueStream()
                .filter(node -> node.get("supplierId").asLong() == supplierId).findFirst().orElseThrow();
        BigDecimal selectedUnitPrice = selected.get("unitPrice").decimalValue();
        jdbc.update("""
                UPDATE supplier_materials SET unit_price = unit_price + 999
                WHERE supplier_id = ? AND material_id = ?
                """, supplierId, material.getId());
        JsonNode retainedSnapshot = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/recommendations/{id}/suppliers", recommendationId)
                                .header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).valueStream()
                .filter(node -> node.get("id").asLong() == selected.get("id").asLong()).findFirst().orElseThrow();
        assertThat(retainedSnapshot.get("unitPrice").decimalValue()).isEqualByComparingTo(selectedUnitPrice);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("materialId", material.getId());
        item.put("quantity", quantity);
        item.put("unitPrice", selected.get("unitPrice").decimalValue());
        item.put("recommendationId", recommendationId);
        item.put("supplierRecommendationResultId", selected.get("id").asLong());
        JsonNode draft = responseJson(post("/api/v1/purchase-orders")
                .header("Authorization", bearer(buyer)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "supplierId", supplierId,
                        "expectedDeliveryDate", baseline.plusDays(12),
                        "items", List.of(item)))), 201);
        long purchaseOrderId = draft.get("id").asLong();

        mockMvc.perform(post("/api/v1/purchase-orders/{id}/approve", purchaseOrderId)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/order", purchaseOrderId)
                        .header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ORDERED"));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/receive", purchaseOrderId)
                        .header("Authorization", bearer(buyer)))
                .andExpect(status().isForbidden());

        BigDecimal before = inventories.findByMaterialId(material.getId()).orElseThrow().getQuantity();
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/receive", purchaseOrderId)
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedBy").value(users.findByUsername("warehouse").orElseThrow().getId()));
        assertThat(inventories.findByMaterialId(material.getId()).orElseThrow().getQuantity())
                .isEqualByComparingTo(before.add(quantity));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_transactions WHERE purchase_order_id = ?", Long.class, purchaseOrderId))
                .isEqualTo(1);
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/receive", purchaseOrderId)
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RECEIVING"));
    }

    @Test
    @Transactional
    void sampleCsvRunsOneSuccessfulImportAndOneValidationFailure() throws Exception {
        String warehouse = login("warehouse");
        Path validPath = Path.of("../samples/inventory-valid.csv");
        MockMultipartFile valid = new MockMultipartFile(
                "file", validPath.getFileName().toString(), "text/csv", Files.readAllBytes(validPath));
        String validation = mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(valid)
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.errorRows").value(0))
                .andReturn().getResponse().getContentAsString();
        long batchId = objectMapper.readTree(validation).get("batchId").asLong();
        mockMvc.perform(post("/api/v1/inventory-imports/{batchId}/confirm", batchId)
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk());

        Path invalidPath = Path.of("../samples/inventory-mixed-errors.csv");
        MockMultipartFile invalid = new MockMultipartFile(
                "file", invalidPath.getFileName().toString(), "text/csv", Files.readAllBytes(invalidPath));
        mockMvc.perform(multipart("/api/v1/inventory-imports/validate").file(invalid)
                        .header("Authorization", bearer(warehouse)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errorRows").value(5));
    }

    private JsonNode responseJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  int expectedStatus) throws Exception {
        String response = mockMvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String bearer(String token) { return "Bearer " + token; }

    private void assertLogin(String username, String password, int expectedStatus, String expectedRole)
            throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().is(expectedStatus));
        if (expectedRole != null) {
            result.andExpect(jsonPath("$.user.role").value(expectedRole));
        }
    }

    private static void writeDynamicPassword(String password) {
        try {
            Files.writeString(DYNAMIC_PASSWORD_FILE, password, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not prepare isolated Demo credential test file", exception);
        }
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("accessToken").asText();
    }
}

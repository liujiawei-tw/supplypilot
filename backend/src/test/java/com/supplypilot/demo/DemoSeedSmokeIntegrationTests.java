package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import com.supplypilot.document.semantic.DocumentSemanticLifecycleReader;
import com.supplypilot.demand.domain.Demand;
import com.supplypilot.demand.repository.DemandRepository;
import com.supplypilot.material.domain.Material;
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
        "spring.config.import=",
        "supplypilot.demo.allowed-database=supplypilot_demo_test",
        "supplypilot.demo.dataset-version=sp-907-zh-tw-v3",
        "supplypilot.demo.samples-path=../samples/documents",
        "supplypilot.document-storage.path=${java.io.tmpdir}/supplypilot-demo-seed-test-documents",
        "supplypilot.semantic.jdbc-url=",
        "supplypilot.jwt.secret=dGVzdC1zZWNyZXQtZm9yLWRlbW8tc2VlZC10ZXN0cy1hdC1sZWFzdC0yNTYtYml0cw==",
        "supplypilot.frontend-origin=http://localhost:5173"
})
@ActiveProfiles("demo")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@AutoConfigureMockMvc
class DemoSeedSmokeIntegrationTests {
    private static final String PASSWORD = "Demo" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String ROTATED_PASSWORD = "New" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    @DynamicPropertySource
    static void demoAccountPassword(DynamicPropertyRegistry registry) {
        registry.add("supplypilot.demo.account-password", () -> PASSWORD);
    }

    @Autowired private UserRepository users;
    @Autowired private MaterialRepository materials;
    @Autowired private DemandRepository demands;
    @Autowired private InventoryRepository inventories;
    @Autowired private SupplierRepository suppliers;
    @Autowired private SupplierMaterialRepository supplierMaterials;
    @Autowired private PurchaseOrderRepository purchaseOrders;
    @Autowired private DocumentMetadataRepository documents;
    @Autowired private DocumentChunkRepository documentChunks;
    @Autowired private DocumentTextExtractor documentTextExtractor;
    @Autowired private DocumentSemanticLifecycleReader semanticLifecycleReader;
    @Autowired private DemoDataCoordinator coordinator;
    @Autowired private com.supplypilot.security.DemoBaselineCredentialAuthenticator demoAuthenticator;
    @Autowired private DemoProperties demoProperties;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DemoCorpusSyncLock corpusSyncLock;

    @Test
    void corpusSyncLockSerializesConcurrentDatabaseSessions() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> corpusSyncLock.withLock(() -> {
                firstEntered.countDown();
                awaitLatch(releaseFirst);
                return "first";
            }));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return corpusSyncLock.withLock(() -> {
                    secondEntered.countDown();
                    return "second";
                });
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondEntered.await(250, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
        } finally {
            releaseFirst.countDown();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the first corpus lock holder");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for corpus lock test", exception);
        }
    }
    @Test
    void onlyAdminBaselineAccountUsesConfiguredPassword() throws Exception {
        assertLogin("admin", PASSWORD, 200, "ADMIN");
        assertLogin("admin", ROTATED_PASSWORD, 401, null);
        for (String username : List.of("manager", "buyer", "warehouse", "planner")) {
            assertLogin(username, PASSWORD, 401, null);
        }
    }

    @Test
    void backendRecreationAppliesNewPasswordWithoutReseedingOrChangingDemoData() throws Exception {
        long usersBefore = users.count();
        long materialsBefore = materials.count();
        long documentsBefore = documents.count();
        long chunksBefore = documentChunks.count();
        long ordersBefore = purchaseOrders.count();

        var recreatedAuthenticator = new com.supplypilot.security.DemoBaselineCredentialAuthenticator(users, ROTATED_PASSWORD);
        assertThatThrownBy(() -> recreatedAuthenticator.authenticate("admin", PASSWORD))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        assertThat(recreatedAuthenticator.authenticate("admin", ROTATED_PASSWORD)).isNotNull();
        assertLogin("admin", PASSWORD, 200, "ADMIN");

        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(materials.count()).isEqualTo(materialsBefore);
        assertThat(documents.count()).isEqualTo(documentsBefore);
        assertThat(documentChunks.count()).isEqualTo(chunksBefore);
        assertThat(purchaseOrders.count()).isEqualTo(ordersBefore);
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
                UserRole.ADMIN, java.time.Instant.now());
        users.saveAndFlush(user);
        try {
            assertLogin(username, "Database123", 200, "ADMIN");
            assertLogin(username, ROTATED_PASSWORD, 401, null);
        } finally {
            users.deleteById(user.getId());
        }
    }

    @Test
    void seedsCompleteDeterministicBaselineAndCanRunAgainWithoutDuplicates() throws Exception {
        assertThat(users.count()).isEqualTo(1);
        assertThat(materials.count()).isEqualTo(12);
        assertThat(suppliers.count()).isEqualTo(10);
        assertThat(supplierMaterials.count()).isEqualTo(45);
        assertThat(demands.count()).isZero();
        assertThat(purchaseOrders.count()).isEqualTo(10);
        var baselineDocuments = documents.findAll().stream().filter(document -> document.isDemoBaseline()).toList();
        assertThat(documents.count()).isEqualTo(115);
        assertThat(baselineDocuments).hasSize(115);
        assertThat(baselineDocuments).allMatch(document -> document.getStatus() == DocumentStatus.READY);
        assertThat(baselineDocuments).extracting(document -> document.getTitle()).hasSize(115);
        assertThat(baselineDocuments).noneMatch(document -> "UI-SUP-005-ADDENDUM"
                .equals(document.getDemoDocumentKey()));
        assertThat(baselineDocuments).allSatisfy(document -> {
            assertThat(document.getVersion()).isEqualTo("1.0");
            assertThat(document.getSemanticReviewStatus()).isNotNull();
            assertThat(document.getSemanticReviewStatus())
                    .isEqualTo(com.supplypilot.document.domain.SemanticReviewStatus.REQUIRES_REVIEW);
            assertThat(document.getSemanticReviewActorType()).isEqualTo("SYSTEM");
            assertThat(document.getSemanticReviewActor()).isEqualTo("TRUSTED_DEMO_CORPUS");
            assertThat(document.getSemanticReviewTrigger()).isEqualTo("TRUSTED_DEMO_CORPUS_AUTO_REVIEW");
            assertThat(Path.of(System.getProperty("java.io.tmpdir"), "supplypilot-demo-seed-test-documents",
                    document.getStoragePath())).isRegularFile();
        });
        Map<DocumentType, Integer> expectedTypeCounts = Map.of(
                DocumentType.SUPPLIER_CONTRACT, 13,
                DocumentType.QUOTATION, 13,
                DocumentType.PROCUREMENT_SOP, 3,
                DocumentType.PRODUCT_SPECIFICATION, 13,
                DocumentType.QUALITY_REQUIREMENT, 13,
                DocumentType.SUPPLIER_PROFILE, 20,
                DocumentType.SUPPLIER_COOPERATION_RECORD, 30,
                DocumentType.SUPPLIER_EVALUATION, 10);
        for (var entry : expectedTypeCounts.entrySet()) {
            assertThat(baselineDocuments.stream()
                    .filter(document -> document.getDocumentType() == entry.getKey())).hasSize(entry.getValue());
        }
        for (int index = 1; index <= 10; index++) {
            String supplierCode = "SUP-%03d".formatted(index);
            Long supplierId = suppliers.findByCode(supplierCode).orElseThrow().getId();
            Long supplierDocumentCount = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM document_metadata
                    WHERE demo_dataset_key = 'SP-901' AND supplier_id = ?
                    """, Long.class, supplierId);
            assertThat(supplierDocumentCount).as("supplier-specific database documents for %s", supplierCode)
                    .isGreaterThanOrEqualTo(10L);
        }
        assertThat(jdbc.queryForObject("""
                SELECT ready_documents FROM demo_corpus_sync_state
                WHERE dataset_key = 'SP-901' AND corpus_version = 'sp-907-zh-tw-v3'
                """, Integer.class)).isEqualTo(115);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM semantic_index_jobs", Integer.class)).isZero();
        assertThat(baselineDocuments).allSatisfy(document -> {
            var lifecycle = semanticLifecycleReader.read(
                    documents.findForSemanticRead(document.getId()).orElseThrow());
            assertThat(lifecycle.status()).isEqualTo("REVIEW_REQUIRED");
            assertThat(lifecycle.status()).isNotEqualTo("INDEXED");
        });
        assertThat(jdbc.queryForObject("SELECT dataset_version FROM demo_dataset_state WHERE dataset_key = 'SP-901'", String.class))
                .isEqualTo("sp-907-zh-tw-v3");
        assertThat(baselineDocuments).allSatisfy(document -> {
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
                PurchaseOrderStatus.DRAFT, 3L,
                PurchaseOrderStatus.APPROVED, 1L,
                PurchaseOrderStatus.ORDERED, 2L,
                PurchaseOrderStatus.RECEIVED, 2L,
                PurchaseOrderStatus.REJECTED, 1L,
                PurchaseOrderStatus.CANCELLED, 1L);
        for (var entry : expected.entrySet()) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE status = ?",
                    Long.class, entry.getKey().name());
            assertThat(count).isEqualTo(entry.getValue());
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT stage) FROM purchase_orders WHERE stage IS NOT NULL", Long.class)).isEqualTo(8L);
        for (String stage : List.of("NEW_REQUEST", "SOURCING", "APPROVAL", "APPROVED_PENDING_ORDER",
                "ORDER_FOLLOW_UP", "RECEIVING_INSPECTION", "PAYMENT_RECONCILIATION", "COMPLETED")) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM purchase_orders WHERE stage = ?", Long.class, stage)).isEqualTo(1L);
        }


        var admin = users.findByUsername("admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isFalse();
        assertThat(admin.canManageUsers()).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isFalse();
        assertThat(users.findAll()).extracting(User::getUsername).containsExactly("admin");
        assertThat(login("admin")).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM demo_dataset_state WHERE dataset_key = 'SP-901'", String.class))
                .isEqualTo("COMPLETE");

        coordinator.initialize();
        assertThat(users.count()).isEqualTo(1);
        assertThat(purchaseOrders.count()).isEqualTo(10);
        assertThat(documents.count()).isEqualTo(115);

        demoProperties.setDatasetVersion("sp-901-v1");
        assertThatThrownBy(coordinator::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configured demo dataset version is invalid");
        assertThat(documents.count()).isEqualTo(115);
        assertThat(jdbc.queryForObject(
                "SELECT dataset_version FROM demo_dataset_state WHERE dataset_key = 'SP-901'", String.class))
                .isEqualTo("sp-907-zh-tw-v3");
        demoProperties.setDatasetVersion("sp-907-zh-tw-v3");
    }

    @Test
    void standardScenarioBUsesRealRecommendationApiAndReturnsSevenHundredFiftyKg() throws Exception {
        Material material = materials.findByCode("MAT-001").orElseThrow();
        Long materialId = material.getId();
        LocalDate baseline = jdbc.queryForObject(
                "SELECT baseline_date FROM demo_dataset_state WHERE dataset_key = 'SP-901'", LocalDate.class);
        createRecommendationTestDemand(material, baseline);
        User recommendationStaff = createRecommendationStaff();
        String token = login(recommendationStaff.getUsername(), "RecommendationTest123");
        String request = """
                {"materialId":%d,"periodStart":"%s","periodEnd":"%s"}
                """.formatted(materialId, baseline, baseline.plusDays(30));

        try {
            mockMvc.perform(post("/api/v1/recommendations")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.calculation.expectedDemand").value(1000))
                    .andExpect(jsonPath("$.calculation.safetyStock").value(200))
                    .andExpect(jsonPath("$.calculation.currentInventory").value(300))
                    .andExpect(jsonPath("$.calculation.incomingInventory").value(0))
                    .andExpect(jsonPath("$.calculation.netRequirement").value(900));
        } finally {
            users.deleteById(recommendationStaff.getId());
        }
    }

    @Test
    @Transactional
    void refusesPartialStateInsteadOfOverwritingExistingBusinessData() {
        jdbc.update("DELETE FROM demo_dataset_state WHERE dataset_key = 'SP-901'");
        assertThatThrownBy(coordinator::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-empty database");
        assertThat(users.count()).isEqualTo(1);
        assertThat(materials.findByCode("MAT-001")).isPresent();
    }

    @Test
    @Transactional
    void runsRecommendationDraftApproveOrderReceiveThroughRealApisAndPreservesAudit() throws Exception {
        var material = materials.findByCode("MAT-001").orElseThrow();
        LocalDate baseline = jdbc.queryForObject(
                "SELECT baseline_date FROM demo_dataset_state WHERE dataset_key = 'SP-901'", LocalDate.class);
        createRecommendationTestDemand(material, baseline);
        String admin = login("admin");
        User recommendationStaff = createRecommendationStaff();
        String recommendationToken = login(recommendationStaff.getUsername(), "RecommendationTest123");

        JsonNode recommendation = responseJson(post("/api/v1/recommendations")
                .header("Authorization", bearer(recommendationToken)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "materialId", material.getId(), "periodStart", baseline,
                        "periodEnd", baseline.plusDays(30)))), 201);
        long recommendationId = recommendation.get("id").asLong();
        long supplierId = recommendation.get("recommendation").get("supplierId").asLong();
        BigDecimal quantity = recommendation.get("recommendation").get("quantity").decimalValue();

        String suppliersJson = mockMvc.perform(get("/api/v1/recommendations/{id}/suppliers", recommendationId)
                        .header("Authorization", bearer(recommendationToken)))
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
                                .header("Authorization", bearer(recommendationToken)))
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
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "supplierId", supplierId,
                        "expectedDeliveryDate", baseline.plusDays(12),
                        "items", List.of(item)))), 201);
        long purchaseOrderId = draft.get("id").asLong();

        mockMvc.perform(post("/api/v1/purchase-orders/{id}/begin-sourcing", purchaseOrderId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("SOURCING"));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/submit-for-approval", purchaseOrderId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("APPROVAL"));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/approve", purchaseOrderId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/order", purchaseOrderId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ORDERED"));

        mockMvc.perform(post("/api/v1/purchase-orders/{id}/begin-receiving-inspection", purchaseOrderId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/purchase-orders/{id}/receiving-inspection", purchaseOrderId)
                        .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allItemsReceived\":true,\"allQuantitiesConfirmed\":true,"
                                + "\"allQualityAccepted\":true,\"note\":\"demo smoke fixture\"}"))
                .andExpect(status().isOk());

        BigDecimal before = inventories.findByMaterialId(material.getId()).orElseThrow().getQuantity();
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/receive", purchaseOrderId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedBy").value(users.findByUsername("admin").orElseThrow().getId()));
        assertThat(inventories.findByMaterialId(material.getId()).orElseThrow().getQuantity())
                .isEqualByComparingTo(before.add(quantity));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_transactions WHERE purchase_order_id = ?", Long.class, purchaseOrderId))
                .isEqualTo(1);
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/receive", purchaseOrderId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RECEIVING"));
    }

    @Test
    @Transactional
    void sampleCsvRunsOneSuccessfulImportAndOneValidationFailure() throws Exception {
        String warehouse = login("admin");
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

    private User createRecommendationStaff() {
        String username = "recommendation_staff_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User user = User.create(username, passwordEncoder.encode("RecommendationTest123"), "Recommendation Test Staff",
                UserRole.PROCUREMENT_STAFF, java.time.Instant.now());
        return users.saveAndFlush(user);
    }

    private void createRecommendationTestDemand(Material material, LocalDate baseline) {
        User admin = users.findByUsername("admin").orElseThrow();
        demands.saveAndFlush(Demand.create(material, new BigDecimal("1000"), baseline.plusDays(15),
                "測試用需求", admin, java.time.Instant.now()));
    }

    private void assertLogin(String username, String password, int expectedStatus, String expectedRole)
            throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().is(expectedStatus));
        if (expectedRole != null) {
            result.andExpect(jsonPath("$.user.role").value(expectedRole));
        }
    }

    private String login(String username) throws Exception {
        return login(username, PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("accessToken").asText();
    }
}

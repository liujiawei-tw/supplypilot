package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.testsupport.FixedClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "spring.config.import=",
        "supplypilot.demo-data-enabled=false",
        "supplypilot.demo.allowed-database=supplypilot_demo_upgrade_test",
        "supplypilot.demo.dataset-version=sp-907-zh-tw-v3",
        "supplypilot.demo.account-password=Demo-Password-For-Tests-901!",
        "supplypilot.document-storage.path=${java.io.tmpdir}/supplypilot-demo-upgrade-test-documents",
        "supplypilot.semantic.indexing.worker-enabled=false",
        "supplypilot.jwt.secret=dGVzdC1zZWNyZXQtZm9yLWRlbW8tdXBncmFkZS10ZXN0LWF0LWxlYXN0LTI1Ni1iaXQ="
})
@ActiveProfiles("demo")
@Import(FixedClockConfiguration.class)
@Testcontainers
class DemoDatasetUpgradeServiceIntegrationTests {
    private static final String DATABASE = "supplypilot_demo_upgrade_test";
    private static final String CURRENT_VERSION = "sp-907-zh-tw-v3";
    private static final String CONFIRMATION = "UPDATE-SP-901@" + DATABASE + "@" + CURRENT_VERSION;
    private static final Instant LEGACY_COMPLETED_AT = Instant.parse("2026-10-20T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final String REJECT_RENAME_CONSTRAINT = "ck_demo_upgrade_test_reject_name";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName(DATABASE)
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", MYSQL::getJdbcUrl);
        registry.add("DB_USERNAME", MYSQL::getUsername);
        registry.add("DB_PASSWORD", MYSQL::getPassword);
    }

    @TempDir
    Path tempDirectory;

    @Autowired private DemoDatasetUpgradeService upgradeService;
    @Autowired private DemoDatasetCoordinator coordinator;
    @Autowired private DemoProperties demoProperties;
    @Autowired private SupplierRepository suppliers;
    @Autowired private SupplierMaterialRepository supplierMaterials;
    @Autowired private MaterialRepository materials;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetIsolatedTestDatabaseAndWriteMasterFixture() throws Exception {
        jdbc.update("DELETE FROM supplier_materials");
        jdbc.update("DELETE FROM demo_dataset_state");
        jdbc.update("DELETE FROM materials");
        jdbc.update("DELETE FROM suppliers");

        Path masterPath = tempDirectory.resolve("demo-master-data.json");
        objectMapper.writeValue(masterPath.toFile(), Map.of(
                "datasetKey", DemoDatasetCoordinator.DATASET_KEY,
                "datasetVersion", CURRENT_VERSION,
                "suppliers", List.of(Map.of(
                        "code", "SUP-001", "name", "福爾摩沙原料工坊", "country", "台灣",
                        "reliabilityScore", "90", "qualityScore", "88", "status", "ACTIVE")),
                "materials", List.of(Map.of(
                        "code", "MAT-001", "name", "阿薩姆紅茶萃取物", "unit", "KG",
                        "safetyStock", "100", "initialInventory", "100", "status", "ACTIVE")),
                "supplierMaterials", List.of()));
        demoProperties.setMasterDataPath(masterPath.toString());
    }

    @Test
    void realMySqlTransactionRollsBackSupplierAndMarkerWhenMaterialWriteFails() {
        seedLegacyBaseline();
        DemoDatasetUpgradeService.UpdatePlan plan = upgradeService.dryRun();
        assertThat(plan.actions()).containsExactly("UPDATE_SUPPLIER:SUP-001", "RENAME_MATERIAL:MAT-001");

        jdbc.execute("ALTER TABLE materials ADD CONSTRAINT " + REJECT_RENAME_CONSTRAINT
                + " CHECK (name <> '阿薩姆紅茶萃取物')");
        try {
            assertThatThrownBy(() -> upgradeService.apply(CONFIRMATION, plan.sha256()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE materials DROP CHECK " + REJECT_RENAME_CONSTRAINT);
        }

        assertThat(suppliers.findByCode("SUP-001").orElseThrow().getName()).isEqualTo("Formosa Ingredients");
        assertThat(materials.findByCode("MAT-001").orElseThrow().getName()).isEqualTo("Assam Tea Extract");
        Map<String, Object> marker = jdbc.queryForMap(
                "SELECT dataset_version, status, completed_at FROM demo_dataset_state WHERE dataset_key = ?",
                DemoDatasetCoordinator.DATASET_KEY);
        assertThat(marker.get("dataset_version")).isEqualTo("sp-905-zh-tw-v1");
        assertThat(marker.get("status")).isEqualTo("COMPLETE");
        assertThat(marker.get("completed_at")).isNotNull();
    }

    @Test
    void concurrentRealMySqlApplySerializesAndRejectsTheSecondStaleApproval() throws Exception {
        seedLegacyBaseline();
        DemoDatasetUpgradeService.UpdatePlan approvedPlan = upgradeService.dryRun();
        CountDownLatch firstApplyReturned = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        CountDownLatch secondApplyStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<DemoDatasetUpgradeService.UpdatePlan> first = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        DemoDatasetUpgradeService.UpdatePlan applied =
                                upgradeService.apply(CONFIRMATION, approvedPlan.sha256());
                        firstApplyReturned.countDown();
                        try {
                            if (!releaseFirstTransaction.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out waiting to commit the first test transaction");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while holding the first test transaction", exception);
                        }
                        return applied;
                    }));

            assertThat(firstApplyReturned.await(10, TimeUnit.SECONDS)).isTrue();
            Future<RuntimeException> second = executor.submit(() -> {
                secondApplyStarted.countDown();
                try {
                    upgradeService.apply(CONFIRMATION, approvedPlan.sha256());
                    return null;
                } catch (RuntimeException exception) {
                    return exception;
                }
            });
            assertThat(secondApplyStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirstTransaction.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).actions()).isEmpty();
            RuntimeException staleApproval = second.get(10, TimeUnit.SECONDS);
            assertThat(staleApproval)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("plan SHA-256 does not match");
        } finally {
            releaseFirstTransaction.countDown();
            executor.shutdownNow();
        }

        assertThat(suppliers.findByCode("SUP-001").orElseThrow().getName()).isEqualTo("福爾摩沙原料工坊");
        assertThat(materials.findByCode("MAT-001").orElseThrow().getName()).isEqualTo("阿薩姆紅茶萃取物");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM demo_dataset_state WHERE dataset_key = ?", String.class,
                DemoDatasetCoordinator.DATASET_KEY)).isEqualTo("UPDATE_REQUIRED");
    }

    @Test
    void concurrentAutoSafeStartsApplyOnlyTheAllowlistedAdditionOnce() throws Exception {
        seedSp907BaselineWithoutSupplier007();
        CountDownLatch firstApplyReturned = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        CountDownLatch secondApplyStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DemoDatasetUpgradeService.UpdatePlan> first = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        DemoDatasetUpgradeService.UpdatePlan result = upgradeService.autoSafe();
                        firstApplyReturned.countDown();
                        try {
                            if (!releaseFirstTransaction.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out waiting to commit the first AUTO_SAFE transaction");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while holding the first AUTO_SAFE transaction",
                                    exception);
                        }
                        return result;
                    }));

            assertThat(firstApplyReturned.await(10, TimeUnit.SECONDS)).isTrue();
            Future<DemoDatasetUpgradeService.UpdatePlan> second = executor.submit(() -> {
                secondApplyStarted.countDown();
                return upgradeService.autoSafe();
            });
            assertThat(secondApplyStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            releaseFirstTransaction.countDown();
            DemoDatasetUpgradeService.UpdatePlan firstPlan = first.get(10, TimeUnit.SECONDS);
            assertThat(firstPlan.actions()).containsExactlyInAnyOrder(
                    "ADD_SUPPLIER:SUP-007",
                    "ADD_LINK:SUP-007:MAT-002",
                    "ADD_LINK:SUP-007:MAT-007",
                    "ADD_LINK:SUP-007:MAT-010",
                    "ADD_LINK:SUP-007:MAT-012");
            assertThat(firstPlan.conflicts()).isEmpty();
            assertThat(second.get(10, TimeUnit.SECONDS).actions()).isEmpty();
        } finally {
            releaseFirstTransaction.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM suppliers WHERE code = ?", Long.class, "SUP-007"))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM supplier_materials sm
                JOIN suppliers s ON s.id = sm.supplier_id
                WHERE s.code = ?
                """, Long.class, "SUP-007")).isEqualTo(4L);
        Map<String, Object> marker = jdbc.queryForMap(
                "SELECT dataset_version, status FROM demo_dataset_state WHERE dataset_key = ?",
                DemoDatasetCoordinator.DATASET_KEY);
        assertThat(marker.get("dataset_version")).isEqualTo(CURRENT_VERSION);
        assertThat(marker.get("status")).isEqualTo("UPDATE_REQUIRED");
    }

    @Test
    void completionWaitsForMarkerLockAndPreservesConcurrentAutoSafeConflict() throws Exception {
        String conflictReason = "AUTO_SAFE preserved relational conflicts; manual review remains required";
        jdbc.update("""
                INSERT INTO demo_dataset_state
                    (dataset_key, dataset_version, baseline_date, status, created_at, completed_at, failure_reason)
                VALUES (?, ?, ?, 'UPDATE_REQUIRED', ?, NULL, NULL)
                """, DemoDatasetCoordinator.DATASET_KEY, CURRENT_VERSION, LocalDate.of(2026, 10, 20),
                Timestamp.from(NOW));

        CountDownLatch writerLocked = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch completionStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> conflictWriter = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(transaction -> {
                    jdbc.queryForObject("SELECT dataset_key FROM demo_dataset_state WHERE dataset_key = ? FOR UPDATE",
                            String.class, DemoDatasetCoordinator.DATASET_KEY);
                    jdbc.update("UPDATE demo_dataset_state SET failure_reason = ? WHERE dataset_key = ?",
                            conflictReason, DemoDatasetCoordinator.DATASET_KEY);
                    writerLocked.countDown();
                    try {
                        if (!releaseWriter.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to release conflict marker lock");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while holding conflict marker lock", exception);
                    }
                }));
        try {
            assertThat(writerLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> completion = executor.submit(() -> {
                completionStarted.countDown();
                return coordinator.completeExistingUpgradeIfReady(readyCorpusReport());
            });
            assertThat(completionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> completion.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseWriter.countDown();
            conflictWriter.get(10, TimeUnit.SECONDS);
            assertThat(completion.get(10, TimeUnit.SECONDS)).isFalse();
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
        }

        Map<String, Object> marker = jdbc.queryForMap(
                "SELECT status, failure_reason FROM demo_dataset_state WHERE dataset_key = ?",
                DemoDatasetCoordinator.DATASET_KEY);
        assertThat(marker.get("status")).isEqualTo("UPDATE_REQUIRED");
        assertThat(marker.get("failure_reason")).isEqualTo(conflictReason);
    }

    private DemoCorpusSyncService.SyncReport readyCorpusReport() {
        return new DemoCorpusSyncService.SyncReport(DemoDatasetCoordinator.DATASET_KEY, CURRENT_VERSION,
                "upgrade-lock-test", DemoCorpusSyncService.SyncStatus.COMPLETE, NOW, NOW,
                115, 0, 0, 115, 115, 0, 0, 0, 0, 0, 0, List.of());
    }

    private void seedSp907BaselineWithoutSupplier007() throws Exception {
        Path packagedMaster = Path.of("..", "samples", "demo-master-data.json").toAbsolutePath().normalize();
        Path testMaster = Path.of(demoProperties.getMasterDataPath());
        java.nio.file.Files.copy(packagedMaster, testMaster, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var master = objectMapper.readTree(packagedMaster.toFile());
        demoProperties.setMasterDataPath(testMaster.toString());

        java.util.Map<String, Supplier> supplierByCode = new java.util.HashMap<>();
        for (var seed : master.path("suppliers")) {
            String code = seed.path("code").asText();
            if ("SUP-007".equals(code)) continue;
            Supplier supplier = Supplier.create(code, seed.path("name").asText(), seed.path("country").asText(),
                    new BigDecimal(seed.path("reliabilityScore").asText()),
                    new BigDecimal(seed.path("qualityScore").asText()), NOW);
            var status = com.supplypilot.supplier.domain.SupplierStatus.valueOf(seed.path("status").asText());
            if (status != com.supplypilot.supplier.domain.SupplierStatus.ACTIVE) {
                supplier.update(null, null, null, null, status, NOW);
            }
            supplierByCode.put(code, suppliers.saveAndFlush(supplier));
        }

        java.util.Map<String, Material> materialByCode = new java.util.HashMap<>();
        for (var seed : master.path("materials")) {
            Material material = Material.create(seed.path("code").asText(), seed.path("name").asText(),
                    BaseUnit.valueOf(seed.path("unit").asText()),
                    new BigDecimal(seed.path("safetyStock").asText()), NOW);
            var status = com.supplypilot.material.domain.MaterialStatus.valueOf(seed.path("status").asText());
            if (status != com.supplypilot.material.domain.MaterialStatus.ACTIVE) {
                material.update(null, null, status, NOW);
            }
            materialByCode.put(material.getCode(), materials.saveAndFlush(material));
        }

        for (var seed : master.path("supplierMaterials")) {
            if ("SUP-007".equals(seed.path("supplierCode").asText())) continue;
            var supplier = supplierByCode.get(seed.path("supplierCode").asText());
            var material = materialByCode.get(seed.path("materialCode").asText());
            var relation = com.supplypilot.supplier.domain.SupplierMaterial.create(supplier, material,
                    new BigDecimal(seed.path("unitPrice").asText()), new BigDecimal(seed.path("moq").asText()),
                    new BigDecimal(seed.path("packageMultiple").asText()), seed.path("leadTimeDays").asInt(),
                    com.supplypilot.supplier.domain.Currency.valueOf(seed.path("currency").asText()), NOW);
            var status = com.supplypilot.supplier.domain.SupplierMaterialStatus.valueOf(seed.path("status").asText());
            if (status != com.supplypilot.supplier.domain.SupplierMaterialStatus.ACTIVE) {
                relation.update(null, null, null, null, status, NOW);
            }
            supplierMaterials.saveAndFlush(relation);
        }

        jdbc.update("""
                INSERT INTO demo_dataset_state
                    (dataset_key, dataset_version, baseline_date, status, created_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETE', ?, ?)
                """, DemoDatasetCoordinator.DATASET_KEY, "sp-906-zh-tw-v2", LocalDate.of(2026, 10, 20),
                Timestamp.from(LEGACY_COMPLETED_AT), Timestamp.from(LEGACY_COMPLETED_AT));
    }

    private void seedLegacyBaseline() {
        suppliers.saveAndFlush(Supplier.create("SUP-001", "Formosa Ingredients", "Taiwan",
                new BigDecimal("90"), new BigDecimal("88"), NOW));
        materials.saveAndFlush(Material.create("MAT-001", "Assam Tea Extract", BaseUnit.KG,
                new BigDecimal("20"), NOW));
        jdbc.update("""
                INSERT INTO demo_dataset_state
                    (dataset_key, dataset_version, baseline_date, status, created_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETE', ?, ?)
                """, DemoDatasetCoordinator.DATASET_KEY, "sp-905-zh-tw-v1", LocalDate.of(2026, 10, 20),
                Timestamp.from(LEGACY_COMPLETED_AT), Timestamp.from(LEGACY_COMPLETED_AT));
    }
}

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
        "supplypilot.demo.dataset-version=sp-906-zh-tw-v2",
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
    private static final String CURRENT_VERSION = "sp-906-zh-tw-v2";
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
    @Autowired private DemoProperties demoProperties;
    @Autowired private SupplierRepository suppliers;
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

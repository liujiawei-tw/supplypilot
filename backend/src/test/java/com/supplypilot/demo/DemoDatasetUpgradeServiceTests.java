package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class DemoDatasetUpgradeServiceTests {
    private static final String DATABASE = "supplypilot_demo";
    private static final String VERSION = "sp-907-zh-tw-v3";
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    @TempDir
    Path tempDirectory;

    private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final SupplierRepository suppliers = org.mockito.Mockito.mock(SupplierRepository.class);
    private final MaterialRepository materials = org.mockito.Mockito.mock(MaterialRepository.class);
    private final SupplierMaterialRepository supplierMaterials = org.mockito.Mockito.mock(SupplierMaterialRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void configureDemoTarget() {
        when(jdbc.queryForObject("SELECT DATABASE()", String.class)).thenReturn(DATABASE);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("dataset_version", "sp-905-zh-tw-v1", "status", "COMPLETE")));
    }

    @Test
    void trustedMasterDataPinAcceptsTrackedBytesAndRejectsSamePathContentChanges() throws Exception {
        byte[] trackedMaster = Files.readAllBytes(Path.of("..", "samples", "demo-master-data.json"));
        byte[] changedMaster = trackedMaster.clone();
        changedMaster[0] ^= 1;
        byte[] changedPolicy = new String(trackedMaster, java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"documentCurrencyAmountsIncluded\": true",
                        "\"documentCurrencyAmountsIncluded\": false")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(DemoDatasetUpgradeService.isPinnedAutoSafeMasterData(trackedMaster)).isTrue();
        assertThat(DemoDatasetUpgradeService.isPinnedAutoSafeMasterData(changedMaster)).isFalse();
        assertThat(DemoDatasetUpgradeService.isPinnedAutoSafeMasterData(changedPolicy)).isFalse();
        assertThat(DemoDatasetCoordinator.isInitialSeedMasterDataTrusted("AUTO_SAFE", trackedMaster)).isTrue();
        assertThat(DemoDatasetCoordinator.isInitialSeedMasterDataTrusted("auto_safe", changedMaster)).isFalse();
        assertThat(DemoDatasetCoordinator.isInitialSeedMasterDataTrusted("AUTO_SAFE", changedPolicy)).isFalse();
        assertThat(DemoDatasetCoordinator.isInitialSeedMasterDataTrusted("DISABLED", changedMaster)).isTrue();
    }

    @Test
    void dryRunPlansNewSupplierAndItsLinksWithoutWriting() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(supplierSeed("SUP-007", "山嵐果萃食品", "台灣")),
                "materials", List.of(materialSeed("MAT-002", "芒果濃縮汁")),
                "supplierMaterials", List.of(linkSeed("SUP-007", "MAT-002"))));
        Material material = Material.create("MAT-002", "芒果濃縮汁", BaseUnit.KG,
                new BigDecimal("20"), NOW);
        when(materials.findByCode("MAT-002")).thenReturn(Optional.of(material));

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).dryRun();

        assertThat(plan.actions()).containsExactly("ADD_SUPPLIER:SUP-007", "ADD_LINK:SUP-007:MAT-002");
        assertThat(plan.sha256()).matches("[0-9a-f]{64}");
        assertThat(plan.manualSteps()).hasSize(3);
        verify(suppliers, never()).saveAndFlush(any(Supplier.class));
        verify(supplierMaterials, never()).saveAndFlush(any());
    }

    @Test
    void applyRequiresTheExactTargetConfirmationAndPlanHash() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(supplierSeed("SUP-007", "山嵐果萃食品", "台灣")),
                "materials", List.of(), "supplierMaterials", List.of()));
        DemoDatasetUpgradeService.UpdatePlan plan = service(master).dryRun();
        DemoDatasetUpgradeService service = service(master);

        assertThatThrownBy(() -> service.apply("UPDATE-SP-901@other-db@" + VERSION, plan.sha256()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmation must exactly match");
        assertThatThrownBy(() -> service.apply("UPDATE-SP-901@" + DATABASE + "@" + VERSION, "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plan SHA-256 does not match");
        verify(suppliers, never()).saveAndFlush(any(Supplier.class));
    }

    @Test
    void dryRunReportsExistingSupplierMaterialDifferencesWithoutChangingPrices() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(supplierSeed("SUP-001", "福爾摩沙原料工坊", "台灣")),
                "materials", List.of(materialSeed("MAT-001", "阿薩姆紅茶萃取物")),
                "supplierMaterials", List.of(linkSeed("SUP-001", "MAT-001"))));
        Supplier supplier = Supplier.create("SUP-001", "福爾摩沙原料工坊", "台灣",
                new BigDecimal("90"), new BigDecimal("88"), NOW);
        Material material = Material.create("MAT-001", "阿薩姆紅茶萃取物", BaseUnit.KG,
                new BigDecimal("20"), NOW);
        SupplierMaterial relation = SupplierMaterial.create(supplier, material, new BigDecimal("101"),
                new BigDecimal("20"), new BigDecimal("6"), 4, Currency.TWD, NOW);
        relation.update(null, null, null, null, SupplierMaterialStatus.INACTIVE, NOW);
        when(suppliers.findByCode("SUP-001")).thenReturn(Optional.of(supplier));
        when(materials.findByCode("MAT-001")).thenReturn(Optional.of(material));
        when(supplierMaterials.findBySupplierIdAndMaterialId(any(), any())).thenReturn(Optional.of(relation));

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).dryRun();

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.conflicts()).containsExactly(
                "SUPPLIER_MATERIAL_PRESERVED:SUP-001:MAT-001 fields=unitPrice,moq,packageMultiple,leadTimeDays,status");
        assertThat(relation.getUnitPrice()).isEqualByComparingTo("101");
        verify(supplierMaterials, never()).saveAndFlush(any(SupplierMaterial.class));
    }

    @Test
    void applyChangesOnlyKnownLegacySupplierDisplayFields() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(supplierSeed("SUP-001", "福爾摩沙原料工坊", "台灣")),
                "materials", List.of(), "supplierMaterials", List.of()));
        Supplier legacy = Supplier.create("SUP-001", "Formosa Ingredients", "Taiwan",
                new BigDecimal("96.00"), new BigDecimal("94.00"), NOW);
        when(suppliers.findByCode("SUP-001")).thenReturn(Optional.of(legacy));
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("dataset_version", "sp-905-zh-tw-v1", "status", "COMPLETE")),
                        List.of(Map.of("dataset_version", VERSION, "status", "UPDATE_REQUIRED")));
        DemoDatasetUpgradeService service = service(master);
        DemoDatasetUpgradeService.UpdatePlan plan = service.dryRun();
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("dataset_version", "sp-905-zh-tw-v1", "status", "COMPLETE")),
                        List.of(Map.of("dataset_version", VERSION, "status", "UPDATE_REQUIRED")));

        service.apply("UPDATE-SP-901@" + DATABASE + "@" + VERSION, plan.sha256());

        assertThat(legacy.getName()).isEqualTo("福爾摩沙原料工坊");
        assertThat(legacy.getCountry()).isEqualTo("台灣");
        assertThat(legacy.getReliabilityScore()).isEqualByComparingTo("96.00");
        assertThat(legacy.getQualityScore()).isEqualByComparingTo("94.00");
        verify(suppliers).saveAndFlush(legacy);
        verify(jdbc).update(anyString(), org.mockito.ArgumentMatchers.eq(VERSION),
                org.mockito.ArgumentMatchers.eq(DemoDatasetCoordinator.DATASET_KEY));
        assertThat(service.dryRun().actions()).isEmpty();
    }

    @Test
    void approvedHashRejectsMasterSupplierNameScoreTermsAndOtherContentChanges() throws Exception {
        List<Map<String, Object>> suppliers = List.of(supplierSeed("SUP-007", "山嵐果萃食品", "台灣"));
        List<Map<String, Object>> materials = List.of(materialSeed("MAT-002", "芒果濃縮汁"));
        List<Map<String, Object>> links = List.of(linkSeed("SUP-007", "MAT-002"));
        Map<String, Object> baseline = masterValues(suppliers, materials, links);

        var changedSupplierName = supplierSeed("SUP-007", "山嵐果萃食品新名", "台灣");
        assertMasterChangeInvalidates(baseline, masterValues(List.of(changedSupplierName), materials, links));

        var changedSupplierScore = new java.util.LinkedHashMap<>(supplierSeed("SUP-007", "山嵐果萃食品", "台灣"));
        changedSupplierScore.put("qualityScore", "89");
        assertMasterChangeInvalidates(baseline, masterValues(List.of(changedSupplierScore), materials, links));

        var changedTerms = new java.util.LinkedHashMap<>(linkSeed("SUP-007", "MAT-002"));
        changedTerms.put("moq", "15");
        assertMasterChangeInvalidates(baseline, masterValues(suppliers, materials, List.of(changedTerms)));

        Map<String, Object> contentChanged = new java.util.LinkedHashMap<>(baseline);
        contentChanged.put("operatorNote", "reviewed after dry-run");
        assertMasterChangeInvalidates(baseline, contentChanged);
    }

    @Test
    void databaseSupplierScoreChangeAfterDryRunIsRejectedUnderWriteLocks() throws Exception {
        Path master = writeMaster(masterValues(
                List.of(supplierSeed("SUP-001", "福爾摩沙原料工坊", "台灣")), List.of(), List.of()));
        Supplier before = Supplier.create("SUP-001", "福爾摩沙原料工坊", "台灣",
                new BigDecimal("90"), new BigDecimal("88"), NOW);
        Supplier concurrentlyChanged = Supplier.create("SUP-001", "福爾摩沙原料工坊", "台灣",
                new BigDecimal("91"), new BigDecimal("88"), NOW.plusSeconds(1));
        when(suppliers.findByCode("SUP-001")).thenReturn(Optional.of(before), Optional.of(before),
                Optional.of(concurrentlyChanged), Optional.of(concurrentlyChanged));
        DemoDatasetUpgradeService service = service(master);
        DemoDatasetUpgradeService.UpdatePlan plan = service.dryRun();

        assertThatThrownBy(() -> service.apply("UPDATE-SP-901@" + DATABASE + "@" + VERSION, plan.sha256()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plan SHA-256 does not match");

        verify(jdbc).queryForList("SELECT id FROM suppliers ORDER BY id FOR UPDATE");
        verify(jdbc).queryForList("SELECT id FROM materials ORDER BY id FOR UPDATE");
        verify(jdbc).queryForList("SELECT id FROM supplier_materials ORDER BY id FOR UPDATE");
        verify(jdbc, never()).update(anyString(), any(), any());
        verify(suppliers, never()).saveAndFlush(any(Supplier.class));
    }

    @Test
    void databaseSupplierMaterialTermsChangeAfterDryRunInvalidatesApprovedHash() throws Exception {
        Path master = writeMaster(masterValues(
                List.of(supplierSeed("SUP-001", "福爾摩沙原料工坊", "台灣")),
                List.of(Map.of("code", "MAT-001", "name", "阿薩姆紅茶萃取物", "unit", "KG",
                        "safetyStock", "200", "initialInventory", "100", "status", "ACTIVE")),
                List.of(linkSeed("SUP-001", "MAT-001"))));
        Supplier supplier = Supplier.create("SUP-001", "福爾摩沙原料工坊", "台灣",
                new BigDecimal("90"), new BigDecimal("88"), NOW);
        Material material = Material.create("MAT-001", "阿薩姆紅茶萃取物", BaseUnit.KG,
                new BigDecimal("20"), NOW);
        SupplierMaterial relation = SupplierMaterial.create(supplier, material, new BigDecimal("101"),
                new BigDecimal("10"), new BigDecimal("5"), 3, Currency.TWD, NOW);
        when(suppliers.findByCode("SUP-001")).thenReturn(Optional.of(supplier));
        when(materials.findByCode("MAT-001")).thenReturn(Optional.of(material));
        when(supplierMaterials.findBySupplierIdAndMaterialId(any(), any())).thenReturn(Optional.of(relation));
        DemoDatasetUpgradeService service = service(master);
        DemoDatasetUpgradeService.UpdatePlan plan = service.dryRun();

        relation.update(new BigDecimal("102"), null, null, null, null, NOW.plusSeconds(1));

        assertThatThrownBy(() -> service.apply("UPDATE-SP-901@" + DATABASE + "@" + VERSION, plan.sha256()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plan SHA-256 does not match");
        verify(supplierMaterials, never()).saveAndFlush(any(SupplierMaterial.class));
        verify(jdbc, never()).update(anyString(), any(), any());
    }

    @Test
    void transactionalApplyRollsBackWhenALaterPlannedWriteFails() throws Exception {
        Path master = writeMaster(masterValues(
                List.of(supplierSeed("SUP-001", "福爾摩沙原料工坊", "台灣")),
                List.of(materialSeed("MAT-001", "阿薩姆紅茶萃取物")), List.of()));
        Supplier legacySupplier = Supplier.create("SUP-001", "Formosa Ingredients", "Taiwan",
                new BigDecimal("90"), new BigDecimal("88"), NOW);
        Material legacyMaterial = Material.create("MAT-001", "Assam Tea Extract", BaseUnit.KG,
                new BigDecimal("20"), NOW);
        when(suppliers.findByCode("SUP-001")).thenReturn(Optional.of(legacySupplier));
        when(materials.findByCode("MAT-001")).thenReturn(Optional.of(legacyMaterial));
        DemoDatasetUpgradeService target = service(master);
        DemoDatasetUpgradeService.UpdatePlan plan = target.dryRun();

        doThrow(new IllegalStateException("injected second write failure"))
                .when(materials).saveAndFlush(any(Material.class));
        PlatformTransactionManager transactionManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = org.mockito.Mockito.mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        DemoDatasetUpgradeService transactionalService = (DemoDatasetUpgradeService) proxyFactory.getProxy();

        assertThatThrownBy(() -> transactionalService.apply(
                "UPDATE-SP-901@" + DATABASE + "@" + VERSION, plan.sha256()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected second write failure");

        verify(suppliers).saveAndFlush(legacySupplier);
        verify(materials).saveAndFlush(legacyMaterial);
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
        verify(jdbc, never()).update(anyString(), any(), any());
    }

    @Test
    void autoSafeAppliesTheLockedAllowlistAndASecondStartupIsIdempotent() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(supplierSeed("SUP-001", "福爾摩沙原料工坊", "台灣")),
                "materials", List.of(), "supplierMaterials", List.of()));
        Supplier legacy = Supplier.create("SUP-001", "Formosa Ingredients", "Taiwan",
                new BigDecimal("92.00"), new BigDecimal("90.00"), NOW);
        when(suppliers.findByCode("SUP-001")).thenReturn(Optional.of(legacy));
        String reason = "AUTO_SAFE relational stage applied; corpus and index status are tracked separately";
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("dataset_version", "sp-905-zh-tw-v1", "status", "COMPLETE",
                                "failure_reason", "")),
                        List.of(Map.of("dataset_version", VERSION, "status", "UPDATE_REQUIRED",
                                "failure_reason", reason)));
        DemoDatasetUpgradeService service = service(master);

        DemoDatasetUpgradeService.UpdatePlan first = service.autoSafe();
        DemoDatasetUpgradeService.UpdatePlan retry = service.autoSafe();

        assertThat(first.actions()).containsExactly("UPDATE_SUPPLIER:SUP-001");
        assertThat(retry.actions()).isEmpty();
        assertThat(legacy.getName()).isEqualTo("福爾摩沙原料工坊");
        verify(suppliers, times(1)).saveAndFlush(legacy);
        verify(jdbc).update(anyString(), org.mockito.ArgumentMatchers.eq(VERSION),
                org.mockito.ArgumentMatchers.eq("UPDATE_REQUIRED"), org.mockito.ArgumentMatchers.eq(reason),
                org.mockito.ArgumentMatchers.eq(DemoDatasetCoordinator.DATASET_KEY));
        verify(jdbc, times(2)).queryForList("SELECT id FROM suppliers ORDER BY id FOR UPDATE");
        verify(jdbc, times(2)).queryForList("SELECT id FROM materials ORDER BY id FOR UPDATE");
        verify(jdbc, times(2)).queryForList("SELECT id FROM supplier_materials ORDER BY id FOR UPDATE");
    }

    @Test
    void autoSafePreservesCustomizedRowsAndRecordsConflictWithoutMarkingComplete() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(supplierSeed("SUP-001", "福爾摩沙原料工坊", "台灣")),
                "materials", List.of(), "supplierMaterials", List.of()));
        Supplier customized = Supplier.create("SUP-001", "使用者自訂名稱", "台灣",
                new BigDecimal("92"), new BigDecimal("90"), NOW);
        when(suppliers.findByCode("SUP-001")).thenReturn(Optional.of(customized));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(
                List.of(Map.of("dataset_version", "sp-906-zh-tw-v2", "status", "COMPLETE",
                        "failure_reason", "")));

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).autoSafe();

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.conflicts()).containsExactly("SUPPLIER_PROFILE_PRESERVED:SUP-001 fields=name");
        assertThat(customized.getName()).isEqualTo("使用者自訂名稱");
        verify(suppliers, never()).saveAndFlush(any(Supplier.class));
        verify(jdbc).update(anyString(), org.mockito.ArgumentMatchers.eq(VERSION),
                org.mockito.ArgumentMatchers.eq("UPDATE_REQUIRED"),
                org.mockito.ArgumentMatchers.eq("AUTO_SAFE preserved relational conflicts; manual review remains required"),
                org.mockito.ArgumentMatchers.eq(DemoDatasetCoordinator.DATASET_KEY));
    }

    @Test
    void autoSafeConflictsOnChangedSupplierScoresAndStatus() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(
                        supplierSeed("SUP-001", "福爾摩沙原料工坊", "台灣"),
                        supplierSeed("SUP-002", "東港貿易社", "台灣")),
                "materials", List.of(), "supplierMaterials", List.of()));
        Supplier changedScore = Supplier.create("SUP-001", "福爾摩沙原料工坊", "台灣",
                new BigDecimal("91"), new BigDecimal("90"), NOW);
        Supplier changedStatus = Supplier.create("SUP-002", "東港貿易社", "台灣",
                new BigDecimal("85"), new BigDecimal("91"), NOW);
        changedStatus.update(null, null, null, null,
                com.supplypilot.supplier.domain.SupplierStatus.INACTIVE, NOW);
        when(suppliers.findByCode("SUP-001")).thenReturn(Optional.of(changedScore));
        when(suppliers.findByCode("SUP-002")).thenReturn(Optional.of(changedStatus));

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).autoSafe();

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.conflicts()).containsExactly(
                "SUPPLIER_PROFILE_PRESERVED:SUP-001 fields=reliabilityScore",
                "SUPPLIER_PROFILE_PRESERVED:SUP-002 fields=status");
        verify(suppliers, never()).saveAndFlush(any(Supplier.class));
    }

    @Test
    void autoSafeConflictsOnChangedMaterialStatusAndSafetyStock() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(),
                "materials", List.of(
                        Map.of("code", "MAT-001", "name", "阿薩姆紅茶萃取物", "unit", "KG",
                                "safetyStock", "200", "initialInventory", "100", "status", "ACTIVE"),
                        Map.of("code", "MAT-002", "name", "芒果濃縮汁", "unit", "KG",
                                "safetyStock", "100", "initialInventory", "100", "status", "ACTIVE")),
                "supplierMaterials", List.of()));
        Material changedStock = Material.create("MAT-001", "阿薩姆紅茶萃取物", BaseUnit.KG,
                new BigDecimal("199"), NOW);
        Material changedStatus = Material.create("MAT-002", "芒果濃縮汁", BaseUnit.KG,
                new BigDecimal("100"), NOW);
        changedStatus.update(null, null, com.supplypilot.material.domain.MaterialStatus.INACTIVE, NOW);
        when(materials.findByCode("MAT-001")).thenReturn(Optional.of(changedStock));
        when(materials.findByCode("MAT-002")).thenReturn(Optional.of(changedStatus));

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).autoSafe();

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.conflicts()).containsExactly(
                "MATERIAL_PROFILE_PRESERVED:MAT-001 fields=safetyStock",
                "MATERIAL_PROFILE_PRESERVED:MAT-002 fields=status");
        verify(materials, never()).saveAndFlush(any(Material.class));
    }

    @Test
    void autoSafeAddsOnlyAnExactAllowlistedNewSupplierMaterialTuple() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(supplierSeed("SUP-007", "山嵐果萃食品", "台灣")),
                "materials", List.of(materialSeed("MAT-002", "芒果濃縮汁")),
                "supplierMaterials", List.of(autoSafeLinkSeed("SUP-007", "MAT-002"))));
        Supplier supplier = Supplier.create("SUP-007", "山嵐果萃食品", "台灣",
                new BigDecimal("90"), new BigDecimal("88"), NOW);
        Material material = Material.create("MAT-002", "芒果濃縮汁", BaseUnit.KG,
                new BigDecimal("100"), NOW);
        when(suppliers.findByCode("SUP-007")).thenReturn(Optional.of(supplier));
        when(materials.findByCode("MAT-002")).thenReturn(Optional.of(material));
        when(supplierMaterials.findBySupplierIdAndMaterialId(any(), any())).thenReturn(Optional.empty());

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).autoSafe();

        assertThat(plan.actions()).containsExactly("ADD_LINK:SUP-007:MAT-002");
        assertThat(plan.conflicts()).isEmpty();
        verify(supplierMaterials).saveAndFlush(any(SupplierMaterial.class));
    }

    @Test
    void autoSafeReportsUnknownSupplierAndNonAllowlistedRelationshipAsConflicts() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(supplierSeed("SUP-011", "未核准供應商", "台灣")),
                "materials", List.of(materialSeed("MAT-001", "阿薩姆紅茶萃取物")),
                "supplierMaterials", List.of(linkSeed("SUP-001", "MAT-001"))));
        Supplier supplier = Supplier.create("SUP-001", "福爾摩沙原料工坊", "台灣",
                new BigDecimal("92"), new BigDecimal("90"), NOW);
        Material material = Material.create("MAT-001", "阿薩姆紅茶萃取物", BaseUnit.KG,
                new BigDecimal("100"), NOW);
        when(suppliers.findByCode("SUP-001")).thenReturn(Optional.of(supplier));
        when(materials.findByCode("MAT-001")).thenReturn(Optional.of(material));
        when(supplierMaterials.findBySupplierIdAndMaterialId(any(), any())).thenReturn(Optional.empty());

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).autoSafe();

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.conflicts()).contains(
                "SUPPLIER_PROFILE_NOT_ALLOWLISTED:SUP-011",
                "LINK_ADDITION_NOT_ALLOWLISTED:SUP-001:MAT-001");
        verify(suppliers, never()).saveAndFlush(any(Supplier.class));
        verify(supplierMaterials, never()).saveAndFlush(any());
    }

    @Test
    void autoSafeRejectsChangedFieldsForAnAllowlistedRelationship() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(supplierSeed("SUP-007", "山嵐果萃食品", "台灣")),
                "materials", List.of(materialSeed("MAT-002", "芒果濃縮汁")),
                "supplierMaterials", List.of(linkSeed("SUP-007", "MAT-002"))));
        Supplier supplier = Supplier.create("SUP-007", "山嵐果萃食品", "台灣",
                new BigDecimal("90"), new BigDecimal("88"), NOW);
        Material material = Material.create("MAT-002", "芒果濃縮汁", BaseUnit.KG,
                new BigDecimal("100"), NOW);
        when(suppliers.findByCode("SUP-007")).thenReturn(Optional.of(supplier));
        when(materials.findByCode("MAT-002")).thenReturn(Optional.of(material));

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).autoSafe();

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.conflicts()).containsExactly("LINK_ADDITION_NOT_ALLOWLISTED:SUP-007:MAT-002");
        verify(supplierMaterials, never()).saveAndFlush(any());
    }

    @Test
    void autoSafeKeepsRelationalReadyMarkerPendingUntilExternalStagesReconcile() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(), "materials", List.of(), "supplierMaterials", List.of()));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(
                List.of(Map.of("dataset_version", VERSION, "status", "RELATIONAL_READY", "failure_reason", "")));

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).autoSafe();

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.sourceStatus()).isEqualTo("RELATIONAL_READY");
        verify(jdbc).update(anyString(), org.mockito.ArgumentMatchers.eq(VERSION),
                org.mockito.ArgumentMatchers.eq("RELATIONAL_READY"),
                org.mockito.ArgumentMatchers.eq("AUTO_SAFE relational stage applied; corpus and index status are tracked separately"),
                org.mockito.ArgumentMatchers.eq(DemoDatasetCoordinator.DATASET_KEY));
    }

    @Test
    void autoSafeDoesNotRewriteACompletedRelationalMarker() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(), "materials", List.of(), "supplierMaterials", List.of()));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(
                List.of(Map.of("dataset_version", VERSION, "status", "COMPLETE", "failure_reason", "")));

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).autoSafe();

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.sourceStatus()).isEqualTo("COMPLETE");
        verify(jdbc, never()).update(anyString(), any(), any());
    }

    @Test
    void autoSafeReportsUnlistedDatabaseRowsAndRelationshipsWithoutChangingThem() throws Exception {
        Path master = writeMaster(masterValues(
                List.of(supplierSeed("SUP-001", "福爾摩沙原料工坊", "台灣")),
                List.of(Map.of("code", "MAT-001", "name", "阿薩姆紅茶萃取物", "unit", "KG",
                        "safetyStock", "200", "initialInventory", "100", "status", "ACTIVE")),
                List.of(linkSeed("SUP-001", "MAT-001"))));
        Supplier targetSupplier = Supplier.create("SUP-001", "福爾摩沙原料工坊", "台灣",
                new BigDecimal("92"), new BigDecimal("90"), NOW);
        Material targetMaterial = Material.create("MAT-001", "阿薩姆紅茶萃取物", BaseUnit.KG,
                new BigDecimal("200"), NOW);
        SupplierMaterial targetLink = SupplierMaterial.create(targetSupplier, targetMaterial,
                new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("5"), 3, Currency.TWD, NOW);
        Supplier extraSupplier = Supplier.create("SUP-999", "使用者自訂供應商", "台灣",
                new BigDecimal("75"), new BigDecimal("80"), NOW);
        Material extraMaterial = Material.create("MAT-999", "使用者自訂物料", BaseUnit.KG,
                new BigDecimal("1"), NOW);
        SupplierMaterial extraLink = SupplierMaterial.create(extraSupplier, extraMaterial,
                new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("1"), 1, Currency.TWD, NOW);
        when(suppliers.findByCode("SUP-001")).thenReturn(Optional.of(targetSupplier));
        when(materials.findByCode("MAT-001")).thenReturn(Optional.of(targetMaterial));
        when(supplierMaterials.findBySupplierIdAndMaterialId(any(), any())).thenReturn(Optional.of(targetLink));
        when(suppliers.findAll()).thenReturn(List.of(targetSupplier, extraSupplier));
        when(materials.findAll()).thenReturn(List.of(targetMaterial, extraMaterial));
        when(supplierMaterials.findAll()).thenReturn(List.of(targetLink, extraLink));

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).autoSafe();

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.conflicts()).containsExactly(
                "SUPPLIER_NOT_IN_TARGET_MASTER:SUP-999",
                "MATERIAL_NOT_IN_TARGET_MASTER:MAT-999",
                "LINK_NOT_IN_TARGET_MASTER:SUP-999:MAT-999");
        assertThat(extraSupplier.getName()).isEqualTo("使用者自訂供應商");
        assertThat(extraMaterial.getName()).isEqualTo("使用者自訂物料");
        assertThat(extraLink.getUnitPrice()).isEqualByComparingTo("10");
        verify(suppliers, never()).saveAndFlush(any(Supplier.class));
        verify(materials, never()).saveAndFlush(any(Material.class));
        verify(supplierMaterials, never()).saveAndFlush(any(SupplierMaterial.class));
    }

    @Test
    void autoSafeAuditsUnlistedDatabaseRowsWhenRelationalMarkerIsAlreadyComplete() throws Exception {
        Path master = writeMaster(masterValues(List.of(), List.of(), List.of()));
        Supplier extraSupplier = Supplier.create("SUP-999", "使用者自訂供應商", "台灣",
                new BigDecimal("75"), new BigDecimal("80"), NOW);
        Material extraMaterial = Material.create("MAT-999", "使用者自訂物料", BaseUnit.KG,
                new BigDecimal("1"), NOW);
        SupplierMaterial extraLink = SupplierMaterial.create(extraSupplier, extraMaterial,
                new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("1"), 1, Currency.TWD, NOW);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(
                List.of(Map.of("dataset_version", VERSION, "status", "COMPLETE", "failure_reason", "")));
        when(suppliers.findAll()).thenReturn(List.of(extraSupplier));
        when(materials.findAll()).thenReturn(List.of(extraMaterial));
        when(supplierMaterials.findAll()).thenReturn(List.of(extraLink));

        DemoDatasetUpgradeService.UpdatePlan plan = service(master).autoSafe();

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.conflicts()).containsExactly(
                "SUPPLIER_NOT_IN_TARGET_MASTER:SUP-999",
                "MATERIAL_NOT_IN_TARGET_MASTER:MAT-999",
                "LINK_NOT_IN_TARGET_MASTER:SUP-999:MAT-999");
        verify(jdbc).update(anyString(),
                org.mockito.ArgumentMatchers.eq("AUTO_SAFE preserved relational conflicts; manual review remains required"),
                org.mockito.ArgumentMatchers.eq(DemoDatasetCoordinator.DATASET_KEY),
                org.mockito.ArgumentMatchers.eq(VERSION));
        verify(suppliers, never()).saveAndFlush(any(Supplier.class));
        verify(materials, never()).saveAndFlush(any(Material.class));
        verify(supplierMaterials, never()).saveAndFlush(any(SupplierMaterial.class));
    }

    @Test
    void autoSafeRejectsMasterContentThatDoesNotMatchItsPinnedDigest() throws Exception {
        Path master = writeMaster(Map.of(
                "suppliers", List.of(supplierSeed("SUP-001", "福爾摩沙原料工坊", "台灣")),
                "materials", List.of(), "supplierMaterials", List.of()));
        DemoDatasetUpgradeService service = service(master, "0".repeat(64));

        assertThatThrownBy(service::autoSafe)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master data SHA-256 does not match the pinned sp907 content");
        verify(suppliers, never()).saveAndFlush(any(Supplier.class));
        verify(jdbc, never()).update(anyString(), any(), any());
    }

    @Test
    void demoPropertiesDefaultToCurrentDatasetAndAutoSafeMode() {
        DemoProperties properties = new DemoProperties();

        assertThat(properties.getDatasetVersion()).isEqualTo(VERSION);
        assertThat(properties.getUpgradeMode()).isEqualTo("AUTO_SAFE");
    }

    private void assertMasterChangeInvalidates(Map<String, Object> baseline,
                                               Map<String, Object> changed) throws Exception {
        Path master = writeMaster(baseline);
        DemoDatasetUpgradeService service = service(master);
        DemoDatasetUpgradeService.UpdatePlan approved = service.dryRun();
        writeMaster(changed);

        assertThatThrownBy(() -> service.apply(
                "UPDATE-SP-901@" + DATABASE + "@" + VERSION, approved.sha256()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plan SHA-256 does not match");
        verify(suppliers, never()).saveAndFlush(any(Supplier.class));
        verify(supplierMaterials, never()).saveAndFlush(any());
    }

    private static Map<String, Object> masterValues(List<Map<String, Object>> suppliers,
                                                     List<Map<String, Object>> materials,
                                                     List<Map<String, Object>> links) {
        return Map.of("suppliers", suppliers, "materials", materials, "supplierMaterials", links);
    }

    private DemoDatasetUpgradeService service(Path master) throws Exception {
        return service(master, sha256(master));
    }

    private DemoDatasetUpgradeService service(Path master, String expectedMasterSha256) {
        DemoProperties properties = new DemoProperties();
        properties.setDatasetVersion(VERSION);
        properties.setMasterDataPath(master.toString());
        properties.setAllowedDatabase(DATABASE);
        return new DemoDatasetUpgradeService(properties, Clock.fixed(NOW, ZoneOffset.UTC), jdbc,
                suppliers, materials, supplierMaterials, objectMapper,
                "./data/demo-documents", expectedMasterSha256);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private Path writeMaster(Map<String, Object> data) throws Exception {
        Path path = tempDirectory.resolve("demo-master-data.json");
        var root = new java.util.LinkedHashMap<String, Object>();
        root.put("datasetKey", DemoDatasetCoordinator.DATASET_KEY);
        root.put("datasetVersion", VERSION);
        root.putAll(data);
        objectMapper.writeValue(path.toFile(), root);
        return path;
    }

    private static Map<String, Object> supplierSeed(String code, String name, String country) {
        String reliability = switch (code) {
            case "SUP-001" -> "92";
            case "SUP-002" -> "85";
            case "SUP-003" -> "88";
            case "SUP-004" -> "70";
            case "SUP-005" -> "94";
            case "SUP-006" -> "82";
            case "SUP-008" -> "87";
            case "SUP-009" -> "84";
            case "SUP-010" -> "89";
            default -> "90";
        };
        String quality = switch (code) {
            case "SUP-001" -> "90";
            case "SUP-002" -> "91";
            case "SUP-003" -> "86";
            case "SUP-004" -> "72";
            case "SUP-005" -> "93";
            case "SUP-006" -> "89";
            case "SUP-008" -> "90";
            case "SUP-009" -> "86";
            case "SUP-010" -> "91";
            default -> "88";
        };
        return Map.of("code", code, "name", name, "country", country,
                "reliabilityScore", reliability, "qualityScore", quality,
                "status", "SUP-004".equals(code) ? "INACTIVE" : "ACTIVE");
    }

    private static Map<String, Object> autoSafeLinkSeed(String supplierCode, String materialCode) {
        return Map.of("supplierCode", supplierCode, "materialCode", materialCode,
                "unitPrice", "92", "currency", "TWD", "moq", "100", "packageMultiple", "25",
                "leadTimeDays", 6, "status", "ACTIVE");
    }

    private static Map<String, Object> materialSeed(String code, String name) {
        return Map.of("code", code, "name", name, "unit", "KG", "safetyStock", "100",
                "initialInventory", "100", "status", "ACTIVE");
    }

    private static Map<String, Object> linkSeed(String supplierCode, String materialCode) {
        return Map.of("supplierCode", supplierCode, "materialCode", materialCode, "unitPrice", "100",
                "currency", "TWD", "moq", "10", "packageMultiple", "5", "leadTimeDays", 3,
                "status", "ACTIVE");
    }
}

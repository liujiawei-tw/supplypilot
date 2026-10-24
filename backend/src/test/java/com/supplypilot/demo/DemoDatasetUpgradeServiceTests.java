package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.nio.file.Path;
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
    private static final String VERSION = "sp-906-zh-tw-v2";
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
                List.of(materialSeed("MAT-001", "阿薩姆紅茶萃取物")),
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

    private DemoDatasetUpgradeService service(Path master) {
        DemoProperties properties = new DemoProperties();
        properties.setDatasetVersion(VERSION);
        properties.setMasterDataPath(master.toString());
        properties.setAllowedDatabase(DATABASE);
        return new DemoDatasetUpgradeService(properties, Clock.fixed(NOW, ZoneOffset.UTC), jdbc,
                suppliers, materials, supplierMaterials, objectMapper, "./data/demo-documents");
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
        return Map.of("code", code, "name", name, "country", country,
                "reliabilityScore", "90", "qualityScore", "88", "status", "ACTIVE");
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

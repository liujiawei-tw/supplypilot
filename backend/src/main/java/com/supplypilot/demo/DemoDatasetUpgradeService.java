package com.supplypilot.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoDatasetUpgradeService {
    private static final Map<String, String> OLD_SUPPLIER_NAMES = Map.of(
            "SUP-001", "Formosa Ingredients",
            "SUP-002", "East Harbor Trading",
            "SUP-003", "Green Leaf Supply",
            "SUP-004", "Old Vendor Co.",
            "SUP-005", "Pacific Food Materials",
            "SUP-006", "Golden Field Trading");
    private static final Map<String, String> OLD_SUPPLIER_COUNTRIES = Map.of(
            "SUP-001", "Taiwan", "SUP-002", "Taiwan", "SUP-003", "Taiwan",
            "SUP-004", "Taiwan", "SUP-005", "Japan", "SUP-006", "Thailand");
    private static final Map<String, String> OLD_MATERIAL_NAMES = Map.ofEntries(
            Map.entry("MAT-001", "Assam Tea Extract"),
            Map.entry("MAT-002", "Mango Concentrate"),
            Map.entry("MAT-003", "Fructose Syrup"),
            Map.entry("MAT-004", "Milk Tea Base"),
            Map.entry("MAT-005", "Tapioca Flavor Powder"),
            Map.entry("MAT-006", "Jasmine Tea Extract"),
            Map.entry("MAT-007", "Passion Fruit Puree"),
            Map.entry("MAT-008", "Brown Sugar Syrup"),
            Map.entry("MAT-009", "Taro Flavor Powder"),
            Map.entry("MAT-010", "Lemon Juice Concentrate"),
            Map.entry("MAT-011", "Oolong Tea Extract"),
            Map.entry("MAT-012", "Coconut Cream Base"));

    private final DemoProperties properties;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final SupplierRepository suppliers;
    private final MaterialRepository materials;
    private final SupplierMaterialRepository supplierMaterials;
    private final ObjectMapper objectMapper;
    private final Path documentStorageRoot;

    public DemoDatasetUpgradeService(
            DemoProperties properties, Clock clock, JdbcTemplate jdbc,
            SupplierRepository suppliers, MaterialRepository materials,
            SupplierMaterialRepository supplierMaterials, ObjectMapper objectMapper,
            @Value("${supplypilot.document-storage.path:./data/demo-documents}") String documentStoragePath) {
        this.properties = properties;
        this.clock = clock;
        this.jdbc = jdbc;
        this.suppliers = suppliers;
        this.materials = materials;
        this.supplierMaterials = supplierMaterials;
        this.objectMapper = objectMapper;
        this.documentStorageRoot = Path.of(documentStoragePath).toAbsolutePath().normalize();
    }

    public UpdatePlan dryRun() {
        String actualDatabase = jdbc.queryForObject("SELECT DATABASE()", String.class);
        DemoTargetGuard.requirePrimaryTargets(actualDatabase, properties.getAllowedDatabase(), documentStorageRoot);
        return buildPlan(loadMasterDataSnapshot(), actualDatabase, state(false));
    }

    private UpdatePlan buildPlan(MasterDataSnapshot snapshot, String actualDatabase, Map<String, Object> state) {
        if (state == null) {
            throw new IllegalStateException("No SP-901 dataset marker exists; use the normal empty-database initializer");
        }
        String currentVersion = String.valueOf(state.get("dataset_version"));
        String currentStatus = String.valueOf(state.get("status"));
        DemoDatasetCoordinator.ExistingDatasetAction stateAction = DemoDatasetCoordinator.classifyExistingDataset(
                currentStatus, currentVersion, properties.getDatasetVersion());
        if (stateAction != DemoDatasetCoordinator.ExistingDatasetAction.LEGACY_UPDATE_REQUIRED
                && stateAction != DemoDatasetCoordinator.ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE) {
            throw new IllegalStateException("Dataset state is already complete or is not eligible for upgrade");
        }

        JsonNode master = snapshot.root();
        List<String> actions = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        planSuppliers(master, actions, conflicts);
        planMaterials(master, actions, conflicts);
        planSupplierMaterials(master, actions, conflicts);
        String databasePreconditionsSha256 = sha256(stableJson(databasePreconditions(master)));

        List<String> manual = List.of(
                "DOCUMENTS: import each seed source as a new version through the admin document workflow; "
                        + "preserve all existing review decisions and re-review every changed source",
                "KANBAN: create fresh demo purchase orders through the UI/API lifecycle for the eight stages; "
                        + "preserve existing PO records and snapshots",
                "MANUAL_UPLOAD: upload ui-upload-sup-005-v1.txt through the UI as a separate review-required document");
        String fingerprint = stableJson(List.of(
                actualDatabase, currentVersion, currentStatus, properties.getDatasetVersion(),
                snapshot.sha256(), databasePreconditionsSha256, actions, conflicts, manual));
        String hash = sha256(fingerprint);
        return new UpdatePlan(actualDatabase, currentVersion, currentStatus, properties.getDatasetVersion(),
                List.copyOf(actions), List.copyOf(conflicts), manual, snapshot.sha256(),
                databasePreconditionsSha256, hash);
    }

    @Transactional
    public UpdatePlan apply(String confirmation, String approvedPlanSha256) {
        String actualDatabase = jdbc.queryForObject("SELECT DATABASE()", String.class);
        DemoTargetGuard.requirePrimaryTargets(actualDatabase, properties.getAllowedDatabase(), documentStorageRoot);
        MasterDataSnapshot snapshot = loadMasterDataSnapshot();
        Map<String, Object> lockedState = state(true);
        lockUpgradeRows();
        UpdatePlan plan = buildPlan(snapshot, actualDatabase, lockedState);
        String expectedConfirmation = "UPDATE-SP-901@" + plan.database() + "@" + plan.targetVersion();
        if (!expectedConfirmation.equals(confirmation)) {
            throw new IllegalStateException("Update confirmation must exactly match " + expectedConfirmation);
        }
        if (approvedPlanSha256 == null || !plan.sha256().equalsIgnoreCase(approvedPlanSha256)) {
            throw new IllegalStateException("Approved dry-run plan SHA-256 does not match current database or master data");
        }

        JsonNode master = snapshot.root();
        for (String action : plan.actions()) {
            if (action.startsWith("UPDATE_SUPPLIER:")) {
                applySupplierUpdate(master, action.substring("UPDATE_SUPPLIER:".length()));
            } else if (action.startsWith("RENAME_MATERIAL:")) {
                applyMaterialRename(master, action.substring("RENAME_MATERIAL:".length()));
            } else if (action.startsWith("ADD_SUPPLIER:")) {
                applySupplierAddition(master, action.substring("ADD_SUPPLIER:".length()));
            } else if (action.startsWith("ADD_LINK:")) {
                applySupplierMaterialAddition(master, action.substring("ADD_LINK:".length()));
            } else {
                throw new IllegalStateException("Unknown safe demo update action: " + action);
            }
        }

        jdbc.update("""
                UPDATE demo_dataset_state
                SET dataset_version = ?, status = 'UPDATE_REQUIRED', completed_at = NULL,
                    failure_reason = 'Manual document and Kanban update steps remain'
                WHERE dataset_key = ?
                """, plan.targetVersion(), DemoDatasetCoordinator.DATASET_KEY);
        Map<String, Object> updatedState = Map.of("dataset_version", plan.targetVersion(), "status", "UPDATE_REQUIRED");
        return buildPlan(snapshot, actualDatabase, updatedState);
    }

    private void lockUpgradeRows() {
        // Lock the dataset marker first, then affected entity tables in a stable order.
        // This serializes concurrent upgrade applies and makes the subsequent snapshot comparison authoritative.
        jdbc.queryForList("SELECT id FROM suppliers ORDER BY id FOR UPDATE");
        jdbc.queryForList("SELECT id FROM materials ORDER BY id FOR UPDATE");
        jdbc.queryForList("SELECT id FROM supplier_materials ORDER BY id FOR UPDATE");
    }

    private void planSuppliers(JsonNode master, List<String> actions, List<String> conflicts) {
        for (JsonNode seed : master.path("suppliers")) {
            String code = seed.path("code").asText();
            var existing = suppliers.findByCode(code).orElse(null);
            if (existing == null) {
                if (!OLD_SUPPLIER_NAMES.containsKey(code)) actions.add("ADD_SUPPLIER:" + code);
                else conflicts.add("SUPPLIER_MISSING:" + code + " (manual review; baseline entity is missing)");
                continue;
            }
            String targetName = seed.path("name").asText();
            String oldName = OLD_SUPPLIER_NAMES.get(code);
            String targetCountry = seed.path("country").asText();
            if (targetName.equals(existing.getName()) && targetCountry.equals(existing.getCountry())) continue;
            String oldCountry = OLD_SUPPLIER_COUNTRIES.get(code);
            if (oldName != null && oldCountry != null
                    && (oldName.equals(existing.getName()) || targetName.equals(existing.getName()))
                    && (oldCountry.equals(existing.getCountry()) || targetCountry.equals(existing.getCountry()))) {
                actions.add("UPDATE_SUPPLIER:" + code);
            } else {
                conflicts.add("SUPPLIER_PROFILE_PRESERVED:" + code);
            }
        }
    }

    private void planMaterials(JsonNode master, List<String> actions, List<String> conflicts) {
        for (JsonNode seed : master.path("materials")) {
            String code = seed.path("code").asText();
            Material existing = materials.findByCode(code).orElse(null);
            if (existing == null) {
                conflicts.add("MATERIAL_MISSING:" + code + " (manual review; inventory baseline is missing)");
                continue;
            }
            String targetName = seed.path("name").asText();
            String oldName = OLD_MATERIAL_NAMES.get(code);
            if (targetName.equals(existing.getName())) continue;
            if (oldName != null && oldName.equals(existing.getName())) {
                actions.add("RENAME_MATERIAL:" + code);
            } else {
                conflicts.add("MATERIAL_NAME_PRESERVED:" + code);
            }
        }
    }

    private void planSupplierMaterials(JsonNode master, List<String> actions, List<String> conflicts) {
        Set<String> plannedSuppliers = actions.stream()
                .filter(action -> action.startsWith("ADD_SUPPLIER:"))
                .map(action -> action.substring("ADD_SUPPLIER:".length()))
                .collect(Collectors.toSet());
        for (JsonNode seed : master.path("supplierMaterials")) {
            String supplierCode = seed.path("supplierCode").asText();
            String materialCode = seed.path("materialCode").asText();
            Supplier supplier = suppliers.findByCode(supplierCode).orElse(null);
            Material material = materials.findByCode(materialCode).orElse(null);
            if ((supplier == null && !plannedSuppliers.contains(supplierCode)) || material == null) {
                conflicts.add("LINK_REFERENCE_MISSING:" + supplierCode + ":" + materialCode);
            } else if (supplier == null) {
                actions.add("ADD_LINK:" + supplierCode + ":" + materialCode);
            } else {
                SupplierMaterial existing = supplierMaterials
                        .findBySupplierIdAndMaterialId(supplier.getId(), material.getId()).orElse(null);
                if (existing == null) {
                    actions.add("ADD_LINK:" + supplierCode + ":" + materialCode);
                } else {
                    List<String> differences = supplierMaterialDifferences(existing, seed);
                    if (!differences.isEmpty()) {
                        conflicts.add("SUPPLIER_MATERIAL_PRESERVED:" + supplierCode + ":" + materialCode
                                + " fields=" + String.join(",", differences));
                    }
                }
            }
        }
    }

    private static List<String> supplierMaterialDifferences(SupplierMaterial existing, JsonNode seed) {
        List<String> differences = new ArrayList<>();
        if (!sameDecimal(existing.getUnitPrice(), seed.path("unitPrice").asText())) differences.add("unitPrice");
        if (!sameDecimal(existing.getMoq(), seed.path("moq").asText())) differences.add("moq");
        if (!sameDecimal(existing.getPackageMultiple(), seed.path("packageMultiple").asText())) {
            differences.add("packageMultiple");
        }
        if (!java.util.Objects.equals(existing.getLeadTimeDays(), seed.path("leadTimeDays").isMissingNode()
                ? null : seed.path("leadTimeDays").asInt())) differences.add("leadTimeDays");
        if (!existing.getCurrency().name().equals(seed.path("currency").asText())) differences.add("currency");
        if (!existing.getStatus().name().equals(seed.path("status").asText())) differences.add("status");
        return differences;
    }

    private static boolean sameDecimal(java.math.BigDecimal current, String target) {
        if (target == null || target.isBlank()) return current == null;
        return current != null && current.compareTo(new java.math.BigDecimal(target)) == 0;
    }

    private void applySupplierUpdate(JsonNode master, String code) {
        Supplier supplier = suppliers.findByCode(code).orElseThrow();
        JsonNode seed = find(master.path("suppliers"), "code", code);
        String oldName = OLD_SUPPLIER_NAMES.get(code);
        String oldCountry = OLD_SUPPLIER_COUNTRIES.get(code);
        String targetName = seed.path("name").asText();
        String targetCountry = seed.path("country").asText();
        if (!(oldName.equals(supplier.getName()) || targetName.equals(supplier.getName()))
                || !(oldCountry.equals(supplier.getCountry()) || targetCountry.equals(supplier.getCountry()))) {
            throw new IllegalStateException("Supplier changed after dry-run; refusing update: " + code);
        }
        supplier.update(targetName, targetCountry, null, null, null, clock.instant());
        suppliers.saveAndFlush(supplier);
    }

    private void applyMaterialRename(JsonNode master, String code) {
        Material material = materials.findByCode(code).orElseThrow();
        JsonNode seed = find(master.path("materials"), "code", code);
        if (!OLD_MATERIAL_NAMES.get(code).equals(material.getName())) {
            throw new IllegalStateException("Material changed after dry-run; refusing update: " + code);
        }
        material.update(seed.path("name").asText(), null, null, clock.instant());
        materials.saveAndFlush(material);
    }

    private void applySupplierAddition(JsonNode master, String code) {
        if (suppliers.existsByCode(code)) throw new IllegalStateException("Supplier added after dry-run: " + code);
        JsonNode seed = find(master.path("suppliers"), "code", code);
        Supplier supplier = Supplier.create(code, seed.path("name").asText(), seed.path("country").asText(),
                new java.math.BigDecimal(seed.path("reliabilityScore").asText()),
                new java.math.BigDecimal(seed.path("qualityScore").asText()), clock.instant());
        SupplierStatus status = SupplierStatus.valueOf(seed.path("status").asText());
        if (status != SupplierStatus.ACTIVE) supplier.update(null, null, null, null, status, clock.instant());
        suppliers.saveAndFlush(supplier);
    }

    private void applySupplierMaterialAddition(JsonNode master, String key) {
        String[] codes = key.split(":", 2);
        JsonNode seed = null;
        for (JsonNode candidate : master.path("supplierMaterials")) {
            if (candidate.path("supplierCode").asText().equals(codes[0])
                    && candidate.path("materialCode").asText().equals(codes[1])) {
                seed = candidate;
                break;
            }
        }
        if (seed == null) throw new IllegalStateException("Unknown demo supplier/material link: " + key);
        Supplier supplier = suppliers.findByCode(codes[0]).orElseThrow();
        Material material = materials.findByCode(codes[1]).orElseThrow();
        if (supplierMaterials.existsBySupplierIdAndMaterialId(supplier.getId(), material.getId())) {
            throw new IllegalStateException("Supplier/material link added after dry-run: " + key);
        }
        SupplierMaterial relation = SupplierMaterial.create(supplier, material,
                new java.math.BigDecimal(seed.path("unitPrice").asText()),
                new java.math.BigDecimal(seed.path("moq").asText()),
                new java.math.BigDecimal(seed.path("packageMultiple").asText()),
                seed.path("leadTimeDays").asInt(), Currency.valueOf(seed.path("currency").asText()), clock.instant());
        SupplierMaterialStatus status = SupplierMaterialStatus.valueOf(seed.path("status").asText());
        if (status != SupplierMaterialStatus.ACTIVE) {
            relation.update(null, null, null, null, status, clock.instant());
        }
        supplierMaterials.saveAndFlush(relation);
    }

    private MasterDataSnapshot loadMasterDataSnapshot() {
        Path path = Path.of(properties.getMasterDataPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalStateException("Demo master data file is missing: " + path);
        try {
            byte[] content = Files.readAllBytes(path);
            JsonNode root = objectMapper.readTree(content);
            if (!root.path("datasetKey").asText().equals(DemoDatasetCoordinator.DATASET_KEY)
                    || !root.path("datasetVersion").asText().equals(properties.getDatasetVersion())) {
                throw new IllegalStateException("Demo master data key/version does not match configured dataset");
            }
            return new MasterDataSnapshot(root, sha256(content));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read demo master data file: " + path, exception);
        }
    }

    private List<List<Object>> databasePreconditions(JsonNode master) {
        List<List<Object>> facts = new ArrayList<>();
        for (JsonNode seed : master.path("suppliers")) {
            String code = seed.path("code").asText();
            Supplier existing = suppliers.findByCode(code).orElse(null);
            facts.add(Arrays.asList("supplier", code, existing == null ? null : existing.getId(),
                    existing == null ? null : existing.getName(), existing == null ? null : existing.getCountry(),
                    existing == null ? null : decimal(existing.getReliabilityScore()),
                    existing == null ? null : decimal(existing.getQualityScore()),
                    existing == null ? null : existing.getStatus().name(),
                    existing == null ? null : String.valueOf(existing.getUpdatedAt())));
        }
        for (JsonNode seed : master.path("materials")) {
            String code = seed.path("code").asText();
            Material existing = materials.findByCode(code).orElse(null);
            facts.add(Arrays.asList("material", code, existing == null ? null : existing.getId(),
                    existing == null ? null : existing.getName(),
                    existing == null ? null : existing.getBaseUnit().name(),
                    existing == null ? null : decimal(existing.getSafetyStock()),
                    existing == null ? null : existing.getStatus().name(),
                    existing == null ? null : String.valueOf(existing.getUpdatedAt())));
        }
        for (JsonNode seed : master.path("supplierMaterials")) {
            String supplierCode = seed.path("supplierCode").asText();
            String materialCode = seed.path("materialCode").asText();
            Supplier supplier = suppliers.findByCode(supplierCode).orElse(null);
            Material material = materials.findByCode(materialCode).orElse(null);
            SupplierMaterial existing = supplier == null || material == null ? null
                    : supplierMaterials.findBySupplierIdAndMaterialId(supplier.getId(), material.getId()).orElse(null);
            facts.add(Arrays.asList("supplierMaterial", supplierCode, materialCode,
                    supplier == null ? null : supplier.getId(), material == null ? null : material.getId(),
                    existing == null ? null : existing.getId(),
                    existing == null ? null : decimal(existing.getUnitPrice()),
                    existing == null ? null : decimal(existing.getMoq()),
                    existing == null ? null : decimal(existing.getPackageMultiple()),
                    existing == null ? null : existing.getLeadTimeDays(),
                    existing == null ? null : existing.getCurrency().name(),
                    existing == null ? null : existing.getStatus().name(),
                    existing == null ? null : String.valueOf(existing.getUpdatedAt())));
        }
        return facts;
    }

    private String stableJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot serialize demo upgrade plan fingerprint", exception);
        }
    }

    private static String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static JsonNode find(JsonNode list, String field, String value) {
        for (JsonNode candidate : list) {
            if (candidate.path(field).asText().equals(value)) return candidate;
        }
        throw new IllegalStateException("Demo master data is missing key " + value);
    }

    private Map<String, Object> state(boolean forUpdate) {
        String lockClause = forUpdate ? " FOR UPDATE" : "";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dataset_version, status FROM demo_dataset_state WHERE dataset_key = ?" + lockClause,
                DemoDatasetCoordinator.DATASET_KEY);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record MasterDataSnapshot(JsonNode root, String sha256) { }

    public record UpdatePlan(
            String database, String sourceVersion, String sourceStatus, String targetVersion,
            List<String> actions, List<String> conflicts, List<String> manualSteps,
            String masterDataSha256, String databasePreconditionsSha256, String sha256) { }
}

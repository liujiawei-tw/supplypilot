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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("demo & !prod")
public class DemoDatasetUpgradeService {
    private static final String AUTO_SAFE_TARGET_VERSION = "sp-907-zh-tw-v3";
    private static final String AUTO_SAFE_MASTER_DATA_SHA256 =
            "6f66e13c66d7a42df96eda3e0b7abde5ba8872db6e53a7555961b0c356ea8245";
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

    private static final Map<String, SafeSupplierProfile> AUTO_SAFE_SUPPLIER_PROFILES = Map.ofEntries(
            Map.entry("SUP-001", new SafeSupplierProfile("\u798f\u723e\u6469\u6c99\u539f\u6599\u5de5\u574a", "\u53f0\u7063", "92", "90", "ACTIVE")),
            Map.entry("SUP-002", new SafeSupplierProfile("\u6771\u6e2f\u8cbf\u6613\u793e", "\u53f0\u7063", "85", "91", "ACTIVE")),
            Map.entry("SUP-003", new SafeSupplierProfile("\u9752\u8449\u98df\u6750\u4f9b\u61c9", "\u53f0\u7063", "88", "86", "ACTIVE")),
            Map.entry("SUP-004", new SafeSupplierProfile("\u820a\u6e2f\u539f\u6599\u884c", "\u53f0\u7063", "70", "72", "INACTIVE")),
            Map.entry("SUP-005", new SafeSupplierProfile("\u6d77\u5cb3\u98df\u54c1\u7d20\u6750", "\u65e5\u672c", "94", "93", "ACTIVE")),
            Map.entry("SUP-006", new SafeSupplierProfile("\u91d1\u7530\u8cbf\u6613", "\u6cf0\u570b", "82", "89", "ACTIVE")),
            Map.entry("SUP-007", new SafeSupplierProfile("\u5c71\u5d50\u679c\u8403\u98df\u54c1", "\u53f0\u7063", "90", "88", "ACTIVE")),
            Map.entry("SUP-008", new SafeSupplierProfile("\u68ee\u5ddd\u98f2\u54c1\u539f\u6599", "\u53f0\u7063", "87", "90", "ACTIVE")),
            Map.entry("SUP-009", new SafeSupplierProfile("\u6615\u5ddd\u6fb1\u7c89\u5de5\u574a", "\u53f0\u7063", "84", "86", "ACTIVE")),
            Map.entry("SUP-010", new SafeSupplierProfile("\u5357\u5cf6\u98a8\u5473\u8cbf\u6613", "\u53f0\u7063", "89", "91", "ACTIVE")));
    private static final Map<String, SafeMaterialProfile> AUTO_SAFE_MATERIAL_PROFILES = Map.ofEntries(
            Map.entry("MAT-001", new SafeMaterialProfile("阿薩姆紅茶萃取物", "KG", "200", "ACTIVE")),
            Map.entry("MAT-002", new SafeMaterialProfile("芒果濃縮汁", "KG", "100", "ACTIVE")),
            Map.entry("MAT-003", new SafeMaterialProfile("果糖糖漿", "KG", "300", "ACTIVE")),
            Map.entry("MAT-004", new SafeMaterialProfile("奶茶基底", "KG", "150", "ACTIVE")),
            Map.entry("MAT-005", new SafeMaterialProfile("珍珠風味粉", "KG", "50", "INACTIVE")),
            Map.entry("MAT-006", new SafeMaterialProfile("茉莉茶萃取物", "KG", "180", "ACTIVE")),
            Map.entry("MAT-007", new SafeMaterialProfile("百香果泥", "KG", "90", "ACTIVE")),
            Map.entry("MAT-008", new SafeMaterialProfile("黑糖糖漿", "KG", "120", "ACTIVE")),
            Map.entry("MAT-009", new SafeMaterialProfile("芋頭風味粉", "KG", "60", "ACTIVE")),
            Map.entry("MAT-010", new SafeMaterialProfile("檸檬汁濃縮液", "KG", "80", "ACTIVE")),
            Map.entry("MAT-011", new SafeMaterialProfile("烏龍茶萃取物", "KG", "160", "ACTIVE")),
            Map.entry("MAT-012", new SafeMaterialProfile("椰奶基底", "KG", "110", "ACTIVE")));
    private static final Map<String, SafeSupplierMaterialProfile> AUTO_SAFE_NEW_SUPPLIER_MATERIALS = Map.ofEntries(
            Map.entry("SUP-007:MAT-002", new SafeSupplierMaterialProfile("92", "100", "25", 6, "TWD", "ACTIVE")),
            Map.entry("SUP-007:MAT-007", new SafeSupplierMaterialProfile("118", "100", "25", 7, "TWD", "ACTIVE")),
            Map.entry("SUP-007:MAT-010", new SafeSupplierMaterialProfile("86", "150", "50", 4, "TWD", "ACTIVE")),
            Map.entry("SUP-007:MAT-012", new SafeSupplierMaterialProfile("145", "80", "20", 8, "TWD", "ACTIVE")),
            Map.entry("SUP-008:MAT-003", new SafeSupplierMaterialProfile("61", "200", "100", 3, "TWD", "ACTIVE")),
            Map.entry("SUP-008:MAT-004", new SafeSupplierMaterialProfile("127", "150", "50", 5, "TWD", "ACTIVE")),
            Map.entry("SUP-008:MAT-006", new SafeSupplierMaterialProfile("105", "100", "25", 4, "TWD", "ACTIVE")),
            Map.entry("SUP-008:MAT-011", new SafeSupplierMaterialProfile("112", "100", "50", 6, "TWD", "ACTIVE")),
            Map.entry("SUP-009:MAT-005", new SafeSupplierMaterialProfile("78", "50", "25", 7, "TWD", "INACTIVE")),
            Map.entry("SUP-009:MAT-008", new SafeSupplierMaterialProfile("75", "100", "25", 5, "TWD", "ACTIVE")),
            Map.entry("SUP-009:MAT-009", new SafeSupplierMaterialProfile("88", "80", "20", 6, "TWD", "ACTIVE")),
            Map.entry("SUP-010:MAT-001", new SafeSupplierMaterialProfile("99", "800", "100", 5, "TWD", "ACTIVE")),
            Map.entry("SUP-010:MAT-006", new SafeSupplierMaterialProfile("108", "100", "25", 5, "TWD", "ACTIVE")),
            Map.entry("SUP-010:MAT-007", new SafeSupplierMaterialProfile("120", "100", "25", 8, "TWD", "ACTIVE")),
            Map.entry("SUP-010:MAT-012", new SafeSupplierMaterialProfile("140", "100", "25", 7, "TWD", "ACTIVE")));

    private final DemoProperties properties;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final SupplierRepository suppliers;
    private final MaterialRepository materials;
    private final SupplierMaterialRepository supplierMaterials;
    private final ObjectMapper objectMapper;
    private final Path documentStorageRoot;
    private final String autoSafeMasterDataSha256;

    @Autowired
    public DemoDatasetUpgradeService(
            DemoProperties properties, Clock clock, JdbcTemplate jdbc,
            SupplierRepository suppliers, MaterialRepository materials,
            SupplierMaterialRepository supplierMaterials, ObjectMapper objectMapper,
            @Value("${supplypilot.document-storage.path:./data/demo-documents}") String documentStoragePath) {
        this(properties, clock, jdbc, suppliers, materials, supplierMaterials, objectMapper,
                documentStoragePath, AUTO_SAFE_MASTER_DATA_SHA256);
    }

    DemoDatasetUpgradeService(
            DemoProperties properties, Clock clock, JdbcTemplate jdbc,
            SupplierRepository suppliers, MaterialRepository materials,
            SupplierMaterialRepository supplierMaterials, ObjectMapper objectMapper,
            String documentStoragePath, String autoSafeMasterDataSha256) {
        this.properties = properties;
        this.clock = clock;
        this.jdbc = jdbc;
        this.suppliers = suppliers;
        this.materials = materials;
        this.supplierMaterials = supplierMaterials;
        this.objectMapper = objectMapper;
        this.documentStorageRoot = Path.of(documentStoragePath).toAbsolutePath().normalize();
        this.autoSafeMasterDataSha256 = autoSafeMasterDataSha256;
    }

    public UpdatePlan dryRun() {
        String actualDatabase = jdbc.queryForObject("SELECT DATABASE()", String.class);
        DemoTargetGuard.requirePrimaryTargets(actualDatabase, properties.getAllowedDatabase(), documentStorageRoot);
        return buildPlan(loadMasterDataSnapshot(), actualDatabase, state(false));
    }

    private UpdatePlan buildPlan(MasterDataSnapshot snapshot, String actualDatabase, Map<String, Object> state) {
        return buildPlan(snapshot, actualDatabase, state, false);
    }

    private UpdatePlan buildPlan(MasterDataSnapshot snapshot, String actualDatabase, Map<String, Object> state,
                                boolean autoSafe) {
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
        planSuppliers(master, actions, conflicts, autoSafe);
        planMaterials(master, actions, conflicts, autoSafe);
        planSupplierMaterials(master, actions, conflicts, autoSafe);
        if (autoSafe) planUnlistedRows(master, conflicts);
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

        applyActions(snapshot.root(), plan.actions());
        jdbc.update("""
                UPDATE demo_dataset_state
                SET dataset_version = ?, status = 'UPDATE_REQUIRED', completed_at = NULL,
                    failure_reason = 'Manual document and Kanban update steps remain'
                WHERE dataset_key = ?
                """, plan.targetVersion(), DemoDatasetCoordinator.DATASET_KEY);
        Map<String, Object> updatedState = Map.of("dataset_version", plan.targetVersion(), "status", "UPDATE_REQUIRED");
        return buildPlan(snapshot, actualDatabase, updatedState);
    }

    @Transactional
    public UpdatePlan autoSafe() {
        if (!AUTO_SAFE_TARGET_VERSION.equals(properties.getDatasetVersion())) {
            throw new IllegalStateException("AUTO_SAFE only supports the pinned sp-907-zh-tw-v3 dataset");
        }
        String actualDatabase = jdbc.queryForObject("SELECT DATABASE()", String.class);
        DemoTargetGuard.requirePrimaryTargets(actualDatabase, properties.getAllowedDatabase(), documentStorageRoot);
        MasterDataSnapshot snapshot = loadMasterDataSnapshot();
        if (!autoSafeMasterDataSha256.equals(snapshot.sha256())) {
            throw new IllegalStateException("AUTO_SAFE packaged master data SHA-256 does not match the pinned sp907 content");
        }
        Map<String, Object> lockedState = state(true);
        if (lockedState == null) {
            throw new IllegalStateException("No SP-901 dataset marker exists; use the normal empty-database initializer");
        }
        lockUpgradeRows();

        String currentVersion = String.valueOf(lockedState.get("dataset_version"));
        String currentStatus = String.valueOf(lockedState.get("status"));
        DemoDatasetCoordinator.ExistingDatasetAction stateAction = DemoDatasetCoordinator.classifyExistingDataset(
                currentStatus, currentVersion, properties.getDatasetVersion());
        if (stateAction == DemoDatasetCoordinator.ExistingDatasetAction.ALREADY_COMPLETE) {
            UpdatePlan audit = noOpPlan(snapshot, actualDatabase, currentVersion, currentStatus);
            if (!audit.conflicts().isEmpty()) {
                jdbc.update("""
                        UPDATE demo_dataset_state
                        SET status = 'UPDATE_REQUIRED', completed_at = NULL, failure_reason = ?
                        WHERE dataset_key = ? AND dataset_version = ? AND status = 'COMPLETE'
                        """, autoSafeFailureReason(audit), DemoDatasetCoordinator.DATASET_KEY,
                        currentVersion);
            }
            return audit;
        }

        // Plan generation and the DB snapshot both occur after the marker/entity locks are held.
        // The transaction commits the allowlisted relational actions and UPDATE_REQUIRED marker together.
        UpdatePlan plan = buildPlan(snapshot, actualDatabase, lockedState, true);
        applyActions(snapshot.root(), plan.actions());

        String failureReason = autoSafeFailureReason(plan);
        boolean alreadyAtTarget =
                stateAction == DemoDatasetCoordinator.ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE;
        String nextStatus = alreadyAtTarget ? currentStatus : "UPDATE_REQUIRED";
        boolean markerNeedsUpdate = !alreadyAtTarget || !plan.actions().isEmpty()
                || !failureReason.equals(lockedState.get("failure_reason"));
        if (markerNeedsUpdate) {
            jdbc.update("""
                    UPDATE demo_dataset_state
                    SET dataset_version = ?, status = ?, completed_at = NULL,
                        failure_reason = ?
                    WHERE dataset_key = ?
                    """, plan.targetVersion(), nextStatus, failureReason, DemoDatasetCoordinator.DATASET_KEY);
        }
        return plan;
    }

    private String autoSafeFailureReason(UpdatePlan plan) {
        if (!plan.conflicts().isEmpty()) {
            return "AUTO_SAFE preserved relational conflicts; manual review remains required";
        }
        return "AUTO_SAFE relational stage applied; corpus and index status are tracked separately";
    }

    private UpdatePlan noOpPlan(MasterDataSnapshot snapshot, String actualDatabase,
                                String currentVersion, String currentStatus) {
        UpdatePlan audit = buildPlan(snapshot, actualDatabase,
                Map.of("dataset_version", currentVersion, "status", "UPDATE_REQUIRED", "failure_reason", ""), true);
        List<String> actions = List.of();
        List<String> conflicts = new ArrayList<>(audit.conflicts());
        audit.actions().forEach(action -> conflicts.add("TARGET_ACTION_REQUIRED:" + action));
        List<String> manual = List.of();
        String databasePreconditionsSha256 = audit.databasePreconditionsSha256();
        String fingerprint = stableJson(List.of(actualDatabase, currentVersion, currentStatus,
                properties.getDatasetVersion(), snapshot.sha256(), databasePreconditionsSha256,
                actions, conflicts, manual));
        return new UpdatePlan(actualDatabase, currentVersion, currentStatus, properties.getDatasetVersion(),
                actions, conflicts, manual, snapshot.sha256(), databasePreconditionsSha256, sha256(fingerprint));
    }

    private void applyActions(JsonNode master, List<String> actions) {
        for (String action : actions) {
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
    }

    private void lockUpgradeRows() {
        // Lock the dataset marker first, then affected entity tables in a stable order.
        // This serializes concurrent upgrade applies and makes the subsequent snapshot comparison authoritative.
        jdbc.queryForList("SELECT id FROM suppliers ORDER BY id FOR UPDATE");
        jdbc.queryForList("SELECT id FROM materials ORDER BY id FOR UPDATE");
        jdbc.queryForList("SELECT id FROM supplier_materials ORDER BY id FOR UPDATE");
    }

    private void planSuppliers(JsonNode master, List<String> actions, List<String> conflicts, boolean autoSafe) {
        for (JsonNode seed : master.path("suppliers")) {
            String code = seed.path("code").asText();
            if (autoSafe && !matchesAutoSafeSupplierProfile(seed)) {
                conflicts.add("SUPPLIER_PROFILE_NOT_ALLOWLISTED:" + code);
                continue;
            }
            var existing = suppliers.findByCode(code).orElse(null);
            if (existing == null) {
                if (autoSafe && !isAutoSafeNewSupplier(code)) {
                    conflicts.add("SUPPLIER_ADDITION_NOT_ALLOWLISTED:" + code);
                } else if (!OLD_SUPPLIER_NAMES.containsKey(code)) {
                    actions.add("ADD_SUPPLIER:" + code);
                } else {
                    conflicts.add("SUPPLIER_MISSING:" + code + " (manual review; baseline entity is missing)");
                }
                continue;
            }
            String targetName = seed.path("name").asText();
            String oldName = OLD_SUPPLIER_NAMES.get(code);
            String targetCountry = seed.path("country").asText();
            if (autoSafe) {
                SafeSupplierProfile expected = AUTO_SAFE_SUPPLIER_PROFILES.get(code);
                List<String> differences = autoSafeSupplierDifferences(existing, expected,
                        oldName, OLD_SUPPLIER_COUNTRIES.get(code), targetName, targetCountry);
                if (!differences.isEmpty()) {
                    conflicts.add("SUPPLIER_PROFILE_PRESERVED:" + code
                            + " fields=" + String.join(",", differences));
                } else if (!targetName.equals(existing.getName())
                        || !targetCountry.equals(existing.getCountry())) {
                    actions.add("UPDATE_SUPPLIER:" + code);
                }
                continue;
            }
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

    private void planMaterials(JsonNode master, List<String> actions, List<String> conflicts, boolean autoSafe) {
        for (JsonNode seed : master.path("materials")) {
            String code = seed.path("code").asText();
            if (autoSafe && !matchesAutoSafeMaterialProfile(seed)) {
                conflicts.add("MATERIAL_TARGET_NOT_ALLOWLISTED:" + code);
                continue;
            }
            Material existing = materials.findByCode(code).orElse(null);
            if (existing == null) {
                conflicts.add("MATERIAL_MISSING:" + code + " (manual review; inventory baseline is missing)");
                continue;
            }
            String targetName = seed.path("name").asText();
            String oldName = OLD_MATERIAL_NAMES.get(code);
            if (autoSafe) {
                SafeMaterialProfile expected = AUTO_SAFE_MATERIAL_PROFILES.get(code);
                List<String> differences = autoSafeMaterialDifferences(existing, expected, oldName, targetName);
                if (!differences.isEmpty()) {
                    conflicts.add("MATERIAL_PROFILE_PRESERVED:" + code
                            + " fields=" + String.join(",", differences));
                } else if (!targetName.equals(existing.getName())) {
                    actions.add("RENAME_MATERIAL:" + code);
                }
                continue;
            }
            if (targetName.equals(existing.getName())) continue;
            if (oldName != null && oldName.equals(existing.getName())) {
                actions.add("RENAME_MATERIAL:" + code);
            } else {
                conflicts.add("MATERIAL_NAME_PRESERVED:" + code);
            }
        }
    }

    private void planSupplierMaterials(JsonNode master, List<String> actions, List<String> conflicts,
                                       boolean autoSafe) {
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
                if (autoSafe && !matchesAutoSafeNewSupplierMaterial(seed)) {
                    conflicts.add("LINK_ADDITION_NOT_ALLOWLISTED:" + supplierCode + ":" + materialCode);
                } else {
                    actions.add("ADD_LINK:" + supplierCode + ":" + materialCode);
                }
            } else {
                SupplierMaterial existing = supplierMaterials
                        .findBySupplierIdAndMaterialId(supplier.getId(), material.getId()).orElse(null);
                if (existing == null) {
                    if (autoSafe && !matchesAutoSafeNewSupplierMaterial(seed)) {
                        conflicts.add("LINK_ADDITION_NOT_ALLOWLISTED:" + supplierCode + ":" + materialCode);
                    } else {
                        actions.add("ADD_LINK:" + supplierCode + ":" + materialCode);
                    }
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

    private void planUnlistedRows(JsonNode master, List<String> conflicts) {
        Set<String> masterSupplierCodes = new java.util.HashSet<>();
        for (JsonNode seed : master.path("suppliers")) masterSupplierCodes.add(seed.path("code").asText());
        suppliers.findAll().stream()
                .sorted(java.util.Comparator.comparing(Supplier::getCode))
                .filter(supplier -> !masterSupplierCodes.contains(supplier.getCode()))
                .forEach(supplier -> conflicts.add("SUPPLIER_NOT_IN_TARGET_MASTER:" + supplier.getCode()));

        Set<String> masterMaterialCodes = new java.util.HashSet<>();
        for (JsonNode seed : master.path("materials")) masterMaterialCodes.add(seed.path("code").asText());
        materials.findAll().stream()
                .sorted(java.util.Comparator.comparing(Material::getCode))
                .filter(material -> !masterMaterialCodes.contains(material.getCode()))
                .forEach(material -> conflicts.add("MATERIAL_NOT_IN_TARGET_MASTER:" + material.getCode()));

        Set<String> masterSupplierMaterialKeys = new java.util.HashSet<>();
        for (JsonNode seed : master.path("supplierMaterials")) {
            masterSupplierMaterialKeys.add(supplierMaterialKey(
                    seed.path("supplierCode").asText(), seed.path("materialCode").asText()));
        }
        supplierMaterials.findAll().stream()
                .sorted(java.util.Comparator.comparing((SupplierMaterial link) -> link.getSupplier().getCode())
                        .thenComparing(link -> link.getMaterial().getCode()))
                .filter(link -> !masterSupplierMaterialKeys.contains(supplierMaterialKey(
                        link.getSupplier().getCode(), link.getMaterial().getCode())))
                .forEach(link -> conflicts.add("LINK_NOT_IN_TARGET_MASTER:"
                        + link.getSupplier().getCode() + ":" + link.getMaterial().getCode()));
    }

    private static String supplierMaterialKey(String supplierCode, String materialCode) {
        return supplierCode + ":" + materialCode;
    }

    private static boolean isAutoSafeNewSupplier(String code) {
        return Set.of("SUP-007", "SUP-008", "SUP-009", "SUP-010").contains(code);
    }

    private static boolean matchesAutoSafeSupplierProfile(JsonNode seed) {
        SafeSupplierProfile expected = AUTO_SAFE_SUPPLIER_PROFILES.get(seed.path("code").asText());
        return expected != null
                && expected.name().equals(seed.path("name").asText())
                && expected.country().equals(seed.path("country").asText())
                && sameDecimalText(seed.path("reliabilityScore").asText(), expected.reliabilityScore())
                && sameDecimalText(seed.path("qualityScore").asText(), expected.qualityScore())
                && expected.status().equals(seed.path("status").asText());
    }

    private static List<String> autoSafeSupplierDifferences(Supplier existing, SafeSupplierProfile expected,
            String oldName, String oldCountry, String targetName, String targetCountry) {
        List<String> differences = new ArrayList<>();
        if (!targetName.equals(existing.getName()) && !java.util.Objects.equals(oldName, existing.getName())) {
            differences.add("name");
        }
        if (!targetCountry.equals(existing.getCountry())
                && !java.util.Objects.equals(oldCountry, existing.getCountry())) differences.add("country");
        if (!sameDecimal(existing.getReliabilityScore(), expected.reliabilityScore())) {
            differences.add("reliabilityScore");
        }
        if (!sameDecimal(existing.getQualityScore(), expected.qualityScore())) differences.add("qualityScore");
        if (!expected.status().equals(existing.getStatus().name())) differences.add("status");
        return differences;
    }

    private static boolean matchesAutoSafeMaterialProfile(JsonNode seed) {
        SafeMaterialProfile expected = AUTO_SAFE_MATERIAL_PROFILES.get(seed.path("code").asText());
        return expected != null
                && expected.name().equals(seed.path("name").asText())
                && expected.baseUnit().equals(seed.path("unit").asText())
                && sameDecimalText(seed.path("safetyStock").asText(), expected.safetyStock())
                && expected.status().equals(seed.path("status").asText());
    }

    private static List<String> autoSafeMaterialDifferences(Material existing, SafeMaterialProfile expected,
            String oldName, String targetName) {
        List<String> differences = new ArrayList<>();
        if (!targetName.equals(existing.getName()) && !java.util.Objects.equals(oldName, existing.getName())) {
            differences.add("name");
        }
        if (!expected.baseUnit().equals(existing.getBaseUnit().name())) differences.add("unit");
        if (!sameDecimal(existing.getSafetyStock(), expected.safetyStock())) differences.add("safetyStock");
        if (!expected.status().equals(existing.getStatus().name())) differences.add("status");
        return differences;
    }

    private static boolean matchesAutoSafeNewSupplierMaterial(JsonNode seed) {
        String key = seed.path("supplierCode").asText() + ":" + seed.path("materialCode").asText();
        SafeSupplierMaterialProfile expected = AUTO_SAFE_NEW_SUPPLIER_MATERIALS.get(key);
        return expected != null
                && sameDecimalText(seed.path("unitPrice").asText(), expected.unitPrice())
                && sameDecimalText(seed.path("moq").asText(), expected.moq())
                && sameDecimalText(seed.path("packageMultiple").asText(), expected.packageMultiple())
                && !seed.path("leadTimeDays").isMissingNode()
                && seed.path("leadTimeDays").asInt() == expected.leadTimeDays()
                && expected.currency().equals(seed.path("currency").asText())
                && expected.status().equals(seed.path("status").asText());
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

    private static boolean sameDecimalText(String current, String expected) {
        if (current == null || current.isBlank()) return false;
        try {
            return new java.math.BigDecimal(current).compareTo(new java.math.BigDecimal(expected)) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
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
                "SELECT dataset_version, status, failure_reason FROM demo_dataset_state WHERE dataset_key = ?" + lockClause,
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

    static boolean isPinnedAutoSafeMasterData(byte[] content) {
        return content != null && AUTO_SAFE_MASTER_DATA_SHA256.equals(sha256(content));
    }

    private record MasterDataSnapshot(JsonNode root, String sha256) { }

    private record SafeSupplierProfile(String name, String country, String reliabilityScore,
                                       String qualityScore, String status) { }

    private record SafeMaterialProfile(String name, String baseUnit, String safetyStock, String status) { }

    private record SafeSupplierMaterialProfile(String unitPrice, String moq, String packageMultiple,
                                               int leadTimeDays, String currency, String status) { }

    public record UpdatePlan(
            String database, String sourceVersion, String sourceStatus, String targetVersion,
            List<String> actions, List<String> conflicts, List<String> manualSteps,
            String masterDataSha256, String databasePreconditionsSha256, String sha256) { }
}

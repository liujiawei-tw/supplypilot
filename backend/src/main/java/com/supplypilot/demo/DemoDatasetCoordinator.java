package com.supplypilot.demo;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.supplypilot.demand.repository.DemandRepository;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.semantic.SemanticStore;
import com.supplypilot.document.service.DocumentStorageService;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.domain.StockTransaction;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.inventory.repository.StockTransactionRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("demo & !prod")
@EnableConfigurationProperties(DemoProperties.class)
public class DemoDatasetCoordinator implements DemoDataCoordinator {
    static final String DATASET_KEY = "SP-901";
    static final String RESET_CONFIRMATION = "RESET-SP-901-DEMO";
    private static final String LEGACY_DATASET_VERSION = "sp-905-zh-tw-v1";
    private static final String PREVIOUS_DATASET_VERSION = "sp-906-zh-tw-v2";
    private static final String CURRENT_DATASET_VERSION = "sp-907-zh-tw-v3";
    private static final int CURRENT_BASELINE_DOCUMENT_COUNT = 115;
    private static final Set<String> SUPPORTED_DATASET_VERSIONS = Set.of(
            LEGACY_DATASET_VERSION, PREVIOUS_DATASET_VERSION, CURRENT_DATASET_VERSION);
    private static final Logger log = LoggerFactory.getLogger(DemoDatasetCoordinator.class);

    private final DemoProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final UserRepository users;
    private final MaterialRepository materials;
    private final InventoryRepository inventories;
    private final SupplierRepository suppliers;
    private final SupplierMaterialRepository supplierMaterials;
    private final DemandRepository demands;
    private final PurchaseOrderRepository purchaseOrders;
    private final StockTransactionRepository stockTransactions;
    private final DocumentMetadataRepository documents;
    private final DocumentStorageService documentStorage;
    private final DemoCorpusSyncService corpusSync;
    private final SemanticStore semanticStore;
    private final SemanticProperties semanticProperties;
    private final Path documentStorageRoot;

    public DemoDatasetCoordinator(
            DemoProperties properties, Clock clock, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, UserRepository users, MaterialRepository materials,
            InventoryRepository inventories, SupplierRepository suppliers,
            SupplierMaterialRepository supplierMaterials, DemandRepository demands,
            PurchaseOrderRepository purchaseOrders, StockTransactionRepository stockTransactions,
            DocumentMetadataRepository documents, DocumentStorageService documentStorage,
            DemoCorpusSyncService corpusSync, SemanticStore semanticStore, SemanticProperties semanticProperties,
            @Value("${supplypilot.document-storage.path:./data/demo-documents}") String documentStoragePath, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.users = users;
        this.materials = materials;
        this.inventories = inventories;
        this.suppliers = suppliers;
        this.supplierMaterials = supplierMaterials;
        this.demands = demands;
        this.purchaseOrders = purchaseOrders;
        this.stockTransactions = stockTransactions;
        this.documents = documents;
        this.documentStorage = documentStorage;
        this.corpusSync = corpusSync;
        this.semanticStore = semanticStore;
        this.semanticProperties = semanticProperties;
        this.documentStorageRoot = Path.of(documentStoragePath).toAbsolutePath().normalize();
    }

    @Override
    public void initialize() {
        requireDemoPrimaryTargets();
        initializeBaseline();
    }

    @Override
    public void resetAndInitialize() {
        requireDemoPrimaryTargets();
        reset();
        initializeBaseline();
    }

    private void initializeBaseline() {
        Map<String, Object> existing = state();
        if (existing != null) {
            ExistingDatasetAction action = classifyExistingDataset(
                    existing.get("status"), existing.get("dataset_version"), properties.getDatasetVersion());
            switch (action) {
                case ALREADY_COMPLETE -> {
                    log.info("DEMO_DATASET_ALREADY_COMPLETE key={} version={} baselineDate={}",
                            DATASET_KEY, existing.get("dataset_version"), existing.get("baseline_date"));
                    if (shouldDeferExistingBaselineSyncToAutoSafe(properties.getUpgradeMode(), action,
                            existing.get("status"))) {
                        deferCorpusSyncToAutoSafe(existing.get("dataset_version"));
                    } else {
                        synchronizeBaseline(false);
                    }
                }
                case LEGACY_UPDATE_REQUIRED -> log.warn("DEMO_DATASET_UPDATE_REQUIRED key={} currentVersion={} "
                                + "currentStatus={} targetVersion={}; safe upgrade runner will preserve existing records",
                        DATASET_KEY, existing.get("dataset_version"), existing.get("status"),
                        properties.getDatasetVersion());
                case MANUAL_STEPS_INCOMPLETE -> {
                    if ("RELATIONAL_READY".equals(existing.get("status"))) {
                        if (shouldDeferExistingBaselineSyncToAutoSafe(properties.getUpgradeMode(), action,
                                existing.get("status"))) {
                            deferCorpusSyncToAutoSafe(existing.get("dataset_version"));
                        } else {
                            synchronizeBaseline(true);
                        }
                    } else {
                        log.warn("DEMO_DATASET_UPDATE_REQUIRED key={} version={} status={} reason={}; "
                                        + "safe upgrade runner will resume eligible work",
                                DATASET_KEY, existing.get("dataset_version"), existing.get("status"),
                                existing.get("failure_reason"));
                    }
                }
            }
            return;
        }

        requireEmptyDatabase();
        LocalDate baselineDate = baselineDate();
        Instant now = clock.instant();
        try {
            // The marker and relational seed share one transaction. A crash cannot leave an
            // INITIALIZING marker that ambiguously describes a partially written baseline.
            transactions.executeWithoutResult(transaction -> {
                jdbc.update("""
                        INSERT INTO demo_dataset_state
                            (dataset_key, dataset_version, baseline_date, status, created_at)
                        VALUES (?, ?, ?, 'RELATIONAL_READY', ?)
                        """, DATASET_KEY, properties.getDatasetVersion(), baselineDate, now);
                seedRelational(baselineDate, now);
            });
        } catch (RuntimeException exception) {
            // Concurrent application starts may race on the unique dataset marker. Continue
            // only when the winning transaction committed a recognized, safe state.
            Map<String, Object> concurrentState = state();
            if (concurrentState != null) {
                ExistingDatasetAction concurrentAction = classifyExistingDataset(
                        concurrentState.get("status"), concurrentState.get("dataset_version"),
                        properties.getDatasetVersion());
                if (concurrentAction == ExistingDatasetAction.ALREADY_COMPLETE) {
                    if (shouldDeferExistingBaselineSyncToAutoSafe(properties.getUpgradeMode(), concurrentAction,
                            concurrentState.get("status"))) {
                        deferCorpusSyncToAutoSafe(concurrentState.get("dataset_version"));
                    } else {
                        synchronizeBaseline(false);
                    }
                    return;
                }
                if (concurrentAction == ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE
                        && "RELATIONAL_READY".equals(concurrentState.get("status"))) {
                    if (shouldDeferExistingBaselineSyncToAutoSafe(properties.getUpgradeMode(), concurrentAction,
                            concurrentState.get("status"))) {
                        deferCorpusSyncToAutoSafe(concurrentState.get("dataset_version"));
                    } else {
                        synchronizeBaseline(true);
                    }
                    return;
                }
            }
            throw exception;
        }
        synchronizeBaseline(true);
    }

    private void synchronizeBaseline(boolean completeRelationalMarkerWhenReady) {
        try {
            DemoCorpusSyncService.SyncReport report = corpusSync.synchronizeBaseline();
            if (completeRelationalMarkerWhenReady) {
                completeExistingUpgradeIfReady(report);
            }
            log.info("DEMO_CORPUS_SYNC key={} version={} status={} total={} created={} adopted={} unchanged={} ready={} "
                            + "approved={} indexQueued={} indexPending={} policyBlocked={} conflicts={} failures={}",
                    DATASET_KEY, properties.getDatasetVersion(), report.status(), report.total(), report.created(),
                    report.adopted(), report.unchanged(), report.ready(), report.approved(), report.indexQueued(),
                    report.pendingIndex(), report.policyBlocked(), report.conflicts(), report.failures());
        } catch (RuntimeException exception) {
            if (completeRelationalMarkerWhenReady) {
                jdbc.update("""
                        UPDATE demo_dataset_state SET failure_reason = ?
                        WHERE dataset_key = ? AND status = 'RELATIONAL_READY'
                        """, "Baseline document sync retry required: "
                        + exception.getClass().getSimpleName(), DATASET_KEY);
            }
            log.error("DEMO_CORPUS_SYNC_RETRY_REQUIRED key={} version={}",
                    DATASET_KEY, properties.getDatasetVersion(), exception);
        }
    }

    private void deferCorpusSyncToAutoSafe(Object version) {
        log.info("DEMO_CORPUS_SYNC_DEFERRED_TO_AUTO_SAFE key={} version={}", DATASET_KEY, version);
    }

    /**
     * Completes the relational dataset marker only after the repository baseline sources are
     * present and locally READY. Approval and semantic publication remain independent lifecycle
     * states and are intentionally not prerequisites for procurement availability.
     */
    public boolean completeExistingUpgradeIfReady(DemoCorpusSyncService.SyncReport report) {
        if (report == null || !CURRENT_DATASET_VERSION.equals(report.corpusVersion())) return false;
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            // AUTO_SAFE also locks this marker before changing failure_reason. Read it with the same
            // lock so completion cannot overwrite a conflict committed while this call was waiting.
            Map<String, Object> current = state(true);
            if (current == null || !CURRENT_DATASET_VERSION.equals(current.get("dataset_version"))) return false;
            String status = String.valueOf(current.get("status"));
            String failureReason = current.get("failure_reason") instanceof String value ? value : null;
            boolean relationalConflict = failureReason != null
                    && failureReason.startsWith("AUTO_SAFE preserved relational conflicts");
            if ("COMPLETE".equals(status)) return !relationalConflict;
            if (!"UPDATE_REQUIRED".equals(status) && !"RELATIONAL_READY".equals(status)) return false;

            boolean sourcesReady = report.status() == DemoCorpusSyncService.SyncStatus.COMPLETE
                    && report.total() == CURRENT_BASELINE_DOCUMENT_COUNT && report.ready() == report.total()
                    && report.conflicts() == 0 && report.failures() == 0;
            if (!sourcesReady || relationalConflict) {
                if ("RELATIONAL_READY".equals(status) && !relationalConflict) {
                    jdbc.update("""
                            UPDATE demo_dataset_state SET failure_reason = ?
                            WHERE dataset_key = ? AND status = 'RELATIONAL_READY'
                            """, "Baseline document reconciliation requires retry", DATASET_KEY);
                }
                return false;
            }
            return jdbc.update("""
                    UPDATE demo_dataset_state
                    SET status = 'COMPLETE', completed_at = ?, failure_reason = NULL
                    WHERE dataset_key = ? AND dataset_version = ? AND status = ?
                    """, clock.instant(), DATASET_KEY, CURRENT_DATASET_VERSION, status) == 1;
        }));
    }

    static ExistingDatasetAction classifyExistingDataset(Object statusValue, Object versionValue,
                                                           String targetVersion) {
        if (!isLegalDatasetVersion(targetVersion)) {
            throw new IllegalStateException("Configured demo dataset version is invalid");
        }
        if (!(statusValue instanceof String status) || !(versionValue instanceof String version)
                || !isLegalDatasetVersion(version)) {
            throw new IllegalStateException("Demo dataset state has an invalid status/version combination");
        }
        return switch (status) {
            case "COMPLETE" -> {
                if (version.equals(targetVersion)) yield ExistingDatasetAction.ALREADY_COMPLETE;
                if (!isOlderDatasetVersion(version, targetVersion)) {
                    throw new IllegalStateException("COMPLETE demo dataset version is not older than the configured version");
                }
                yield ExistingDatasetAction.LEGACY_UPDATE_REQUIRED;
            }
            case "RELATIONAL_READY" -> {
                if (version.equals(targetVersion)) yield ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE;
                if (!isOlderDatasetVersion(version, targetVersion)) {
                    throw new IllegalStateException("RELATIONAL_READY demo dataset version is not older than the configured version");
                }
                yield ExistingDatasetAction.LEGACY_UPDATE_REQUIRED;
            }
            case "UPDATE_REQUIRED" -> {
                if (!version.equals(targetVersion)) {
                    throw new IllegalStateException("UPDATE_REQUIRED demo dataset must use the configured version");
                }
                yield ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE;
            }
            case "INITIALIZING", "FAILED" -> throw new IllegalStateException(
                    "Demo dataset is not complete; refusing to treat status " + status + " as a baseline");
            default -> throw new IllegalStateException("Unknown demo dataset status: " + status);
        };
    }

    static boolean shouldDeferExistingBaselineSyncToAutoSafe(String upgradeMode,
                                                              ExistingDatasetAction action,
                                                              Object statusValue) {
        if (!"AUTO_SAFE".equalsIgnoreCase(upgradeMode)) return false;
        return (action == ExistingDatasetAction.ALREADY_COMPLETE && "COMPLETE".equals(statusValue))
                || (action == ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE
                && "RELATIONAL_READY".equals(statusValue));
    }

    static boolean isInitialSeedMasterDataTrusted(String upgradeMode, byte[] content) {
        return !"AUTO_SAFE".equalsIgnoreCase(upgradeMode)
                || DemoDatasetUpgradeService.isPinnedAutoSafeMasterData(content);
    }

    private static boolean isLegalDatasetVersion(String version) {
        return version != null && SUPPORTED_DATASET_VERSIONS.contains(version);
    }

    private static boolean isOlderDatasetVersion(String candidate, String target) {
        int candidateRank = datasetVersionRank(candidate);
        int targetRank = datasetVersionRank(target);
        return candidateRank >= 0 && targetRank >= 0 && candidateRank < targetRank;
    }

    private static int datasetVersionRank(String version) {
        if (LEGACY_DATASET_VERSION.equals(version)) return 1;
        if (PREVIOUS_DATASET_VERSION.equals(version)) return 2;
        if (CURRENT_DATASET_VERSION.equals(version)) return 3;
        return -1;
    }

    enum ExistingDatasetAction {
        ALREADY_COMPLETE,
        LEGACY_UPDATE_REQUIRED,
        MANUAL_STEPS_INCOMPLETE
    }

    private void seedRelational(LocalDate baselineDate, Instant now) {
        JsonNode master = loadMasterData();
        Map<String, User> userMap = seedUsers(now);
        Map<String, Material> materialMap = seedMaterialsAndInventory(master, now);
        Map<String, com.supplypilot.supplier.domain.Supplier> supplierMap = seedSuppliers(master, now);
        Map<String, SupplierMaterial> supplierMaterialMap = seedSupplierMaterials(
                master, supplierMap, materialMap, now);
        seedPurchaseOrders(master, materialMap, supplierMap, supplierMaterialMap, userMap, baselineDate, now);
    }

    private JsonNode loadMasterData() {
        Path path = Path.of(properties.getMasterDataPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Demo master data file is missing: " + path);
        }
        try {
            byte[] content = Files.readAllBytes(path);
            if (!isInitialSeedMasterDataTrusted(properties.getUpgradeMode(), content)) {
                throw new IllegalStateException(
                        "AUTO_SAFE packaged master data SHA-256 does not match the pinned sp907 content");
            }
            JsonNode root = objectMapper.readTree(content);
            if (!root.path("datasetKey").asText().equals(DATASET_KEY)
                    || !root.path("datasetVersion").asText().equals(properties.getDatasetVersion())) {
                throw new IllegalStateException("Demo master data key/version does not match configured dataset");
            }
            return root;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read demo master data file: " + path, exception);
        }
    }

    private Map<String, User> seedUsers(Instant now) {
        // Baseline accounts use the Demo profile's configured password, not the database hash.
        // Keep a random, unusable BCrypt value so BCrypt authentication cannot become a fallback.
        String hash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
        Map<String, User> result = new LinkedHashMap<>();
        result.put("admin", User.create("admin", hash, "澄野茶飲展示管理員", UserRole.ADMIN, true, now));
        users.saveAll(result.values());
        users.flush();
        return result;
    }

    private Map<String, Material> seedMaterialsAndInventory(JsonNode master, Instant now) {
        Map<String, Material> result = new LinkedHashMap<>();
        for (JsonNode seed : master.path("materials")) {
            String code = seed.path("code").asText();
            Material material = Material.create(code, seed.path("name").asText(),
                    BaseUnit.valueOf(seed.path("unit").asText()), decimal(seed.path("safetyStock").asText()), now);
            MaterialStatus status = MaterialStatus.valueOf(seed.path("status").asText());
            if (status != MaterialStatus.ACTIVE) material.update(null, null, status, now);
            materials.save(material);
            Inventory inventory = Inventory.create(material, now);
            inventory.replaceSnapshot(decimal(seed.path("initialInventory").asText()), now);
            inventories.save(inventory);
            result.put(code, material);
        }
        materials.flush();
        inventories.flush();
        return result;
    }

    private Map<String, com.supplypilot.supplier.domain.Supplier> seedSuppliers(JsonNode master, Instant now) {
        Map<String, com.supplypilot.supplier.domain.Supplier> result = new LinkedHashMap<>();
        for (JsonNode seed : master.path("suppliers")) {
            String code = seed.path("code").asText();
            var supplier = com.supplypilot.supplier.domain.Supplier.create(code, seed.path("name").asText(),
                    seed.path("country").asText(), decimal(seed.path("reliabilityScore").asText()),
                    decimal(seed.path("qualityScore").asText()), now);
            SupplierStatus status = SupplierStatus.valueOf(seed.path("status").asText());
            if (status != SupplierStatus.ACTIVE) supplier.update(null, null, null, null, status, now);
            suppliers.save(supplier);
            result.put(code, supplier);
        }
        suppliers.flush();
        return result;
    }

    private Map<String, SupplierMaterial> seedSupplierMaterials(
            JsonNode master, Map<String, com.supplypilot.supplier.domain.Supplier> supplierMap,
            Map<String, Material> materialMap, Instant now) {
        Map<String, SupplierMaterial> result = new LinkedHashMap<>();
        for (JsonNode seed : master.path("supplierMaterials")) {
            String supplierCode = seed.path("supplierCode").asText();
            String materialCode = seed.path("materialCode").asText();
            boolean active = SupplierMaterialStatus.valueOf(seed.path("status").asText())
                    == SupplierMaterialStatus.ACTIVE;
            addSupplierMaterial(result, supplierMap, materialMap, supplierCode, materialCode,
                    seed.path("unitPrice").asText(), seed.path("moq").asText(),
                    seed.path("packageMultiple").asText(), seed.path("leadTimeDays").asInt(), active, now);
        }
        supplierMaterials.flush();
        return result;
    }

    private void addSupplierMaterial(Map<String, SupplierMaterial> result,
                                     Map<String, com.supplypilot.supplier.domain.Supplier> supplierMap,
                                     Map<String, Material> materialMap, String supplierCode, String materialCode,
                                     String price, String moq, String multiple, int leadTime, boolean active, Instant now) {
        var supplier = supplierMap.get(supplierCode);
        var material = materialMap.get(materialCode);
        if (supplier == null || material == null) {
            throw new IllegalStateException("Unknown demo supplier/material link: " + supplierCode + ":" + materialCode);
        }
        SupplierMaterial relation = SupplierMaterial.create(supplier, material,
                decimal(price), decimal(moq), decimal(multiple), leadTime, Currency.TWD, now);
        if (!active) relation.update(null, null, null, null, SupplierMaterialStatus.INACTIVE, now);
        supplierMaterials.save(relation);
        result.put(supplierCode + ":" + materialCode, relation);
    }

    private void seedPurchaseOrders(JsonNode master,
                                    Map<String, Material> materialMap,
                                    Map<String, com.supplypilot.supplier.domain.Supplier> supplierMap,
                                    Map<String, SupplierMaterial> relationMap, Map<String, User> userMap,
                                    LocalDate date, Instant now) {
        for (JsonNode seed : master.path("purchaseOrderExamples")) {
            createPo(seed, materialMap, supplierMap, relationMap, userMap, date, now);
        }
    }

    private void createPo(JsonNode seed, Map<String, Material> materialMap,
                          Map<String, com.supplypilot.supplier.domain.Supplier> supplierMap,
                          Map<String, SupplierMaterial> relationMap, Map<String, User> userMap,
                          LocalDate date, Instant now) {
        String seedKey = seed.path("seedKey").asText();
        int sequence = Integer.parseInt(seedKey.substring(seedKey.lastIndexOf('-') + 1));
        String supplierCode = seed.path("supplierCode").asText();
        String materialCode = seed.path("materialCode").asText();
        BigDecimal quantity = decimal(seed.path("quantity").asText());
        LocalDate deliveryDate = date.plusDays(seed.path("deliveryOffsetDays").asInt());
        Material material = materialMap.get(materialCode);
        var supplier = supplierMap.get(supplierCode);
        SupplierMaterial relation = relationMap.get(supplierCode + ":" + materialCode);
        if (material == null || supplier == null || relation == null) {
            throw new IllegalStateException("Demo PO references an unknown supplier/material relation: " + seedKey);
        }

        PurchaseOrder order = PurchaseOrder.create(supplier, deliveryDate, userMap.get("admin"), now);
        order.assignPendingPoNumber("DEMO-PENDING-" + sequence);
        order.addItem(PurchaseOrderItem.create(material, quantity, relation.getUnitPrice(), now));
        purchaseOrders.saveAndFlush(order);
        order.assignDisplayPoNumber("PO-%04d%02d%02d-%06d".formatted(deliveryDate.getYear(),
                deliveryDate.getMonthValue(), deliveryDate.getDayOfMonth(), order.getId()));

        String status = seed.path("status").asText();
        if ("REJECTED".equals(status)) {
            order.reject(seed.path("rejectionReason").asText(), userMap.get("admin"), now);
        } else if ("CANCELLED".equals(status)) {
            order.advanceFromNewRequest(now);
            order.advanceFromSourcing(now);
            order.approve(userMap.get("admin"), now);
            order.cancel(seed.path("cancellationReason").asText(), userMap.get("admin"), now);
        } else {
            String targetStage = seed.path("stage").asText();
            if (!"NEW_REQUEST".equals(targetStage)) order.advanceFromNewRequest(now);
            if (!List.of("NEW_REQUEST", "SOURCING").contains(targetStage)) order.advanceFromSourcing(now);
            if (List.of("APPROVED_PENDING_ORDER", "ORDER_FOLLOW_UP", "RECEIVING_INSPECTION",
                    "PAYMENT_RECONCILIATION", "COMPLETED").contains(targetStage)) {
                order.approve(userMap.get("admin"), now);
            }
            if (List.of("ORDER_FOLLOW_UP", "RECEIVING_INSPECTION", "PAYMENT_RECONCILIATION",
                    "COMPLETED").contains(targetStage)) {
                order.markOrdered(userMap.get("admin"), now);
            }
            if (List.of("RECEIVING_INSPECTION", "PAYMENT_RECONCILIATION", "COMPLETED").contains(targetStage)) {
                order.beginReceivingInspection(now);
                order.recordReceivingInspection(true, true, true, "展示收貨檢驗：品項、數量與品質均已確認",
                        userMap.get("admin"), now);
            }
            if (List.of("PAYMENT_RECONCILIATION", "COMPLETED").contains(targetStage)) {
                Inventory inventory = inventories.findByMaterialId(material.getId()).orElseThrow();
                inventory.receive(quantity, now);
                inventories.save(inventory);
                stockTransactions.save(StockTransaction.purchaseReceive(
                        material, quantity, order.getId(), userMap.get("admin"), now));
                order.receive(userMap.get("admin"), now);
            }
            if ("COMPLETED".equals(targetStage)) order.confirmPayment(userMap.get("admin"), now);
        }
        purchaseOrders.saveAndFlush(order);
    }

    static List<JsonNode> requireValidSeedDocuments(JsonNode catalog) {
        if (!catalog.path("documents").isArray()) {
            throw new IllegalStateException("Demo corpus catalog documents must be an array");
        }
        List<JsonNode> seedDocuments = new java.util.ArrayList<>();
        EnumSet<DocumentType> coveredTypes = EnumSet.noneOf(DocumentType.class);
        for (JsonNode document : catalog.path("documents")) {
            if ("SEED_BASELINE".equals(document.path("classification").asText())) {
                seedDocuments.add(document);
                try {
                    coveredTypes.add(DocumentType.valueOf(document.path("type").asText()));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalStateException("Unsupported demo corpus document type: "
                            + document.path("type").asText(), exception);
                }
            } else if (!"MANUAL_UI_UPLOAD_ONLY".equals(document.path("classification").asText())) {
                throw new IllegalStateException("Unknown demo document classification: "
                        + document.path("documentKey").asText());
            }
        }
        if (seedDocuments.isEmpty() || !catalog.path("seedDocumentCount").isIntegralNumber()
                || seedDocuments.size() != catalog.path("seedDocumentCount").asInt()) {
            throw new IllegalStateException("Demo corpus seedDocumentCount must match its non-empty baseline catalog");
        }
        if (!coveredTypes.containsAll(EnumSet.allOf(DocumentType.class))) {
            EnumSet<DocumentType> missingTypes = EnumSet.allOf(DocumentType.class);
            missingTypes.removeAll(coveredTypes);
            throw new IllegalStateException("Demo corpus is missing required seed document types: " + missingTypes);
        }
        return List.copyOf(seedDocuments);
    }

    private void reset() {
        if (!RESET_CONFIRMATION.equals(properties.getResetConfirmation())) {
            throw new IllegalStateException("Demo reset confirmation is missing or invalid");
        }
        if (!isExplicitDemoTargetName(properties.getAllowedSemanticDatabase())) {
            throw new IllegalStateException("The allowed semantic database is not explicitly demo-isolated");
        }
        String semanticUrl = semanticProperties.jdbcUrl();
        if (semanticUrl == null || !databaseName(semanticUrl).equals(properties.getAllowedSemanticDatabase())) {
            throw new IllegalStateException("Refusing reset without the explicitly allowed demo semantic database");
        }

        semanticStore.resetAllDemoData(properties.getAllowedSemanticDatabase());
        for (var document : documents.findAll()) {
            documentStorage.delete(document.getStoragePath());
        }
        transactions.executeWithoutResult(transaction -> {
            for (String table : List.of(
                    "ai_purchase_draft_proposals", "demo_corpus_sync_outcomes", "demo_corpus_sync_conflicts",
                    "document_chunks", "document_metadata",
                    "inventory_import_items", "inventory_import_batches", "stock_transactions",
                    "purchase_order_items", "purchase_orders", "supplier_recommendation_result",
                    "purchase_recommendation", "demands", "supplier_materials", "inventory",
                    "materials", "suppliers", "ai_monthly_token_usage", "users", "demo_dataset_state")) {
                jdbc.update("DELETE FROM " + table);
            }
        });
        log.warn("DEMO_DATASET_RESET database={} semanticDatabase={}", properties.getAllowedDatabase(),
                properties.getAllowedSemanticDatabase());
    }

    private void requireDemoPrimaryTargets() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        DemoTargetGuard.requirePrimaryTargets(database, properties.getAllowedDatabase(), documentStorageRoot);
    }

    static boolean isExplicitDemoTargetName(String value) {
        return DemoTargetGuard.isExplicitDemoTargetName(value);
    }

    private void requireEmptyDatabase() {
        if (users.count() != 0 || materials.count() != 0 || inventories.count() != 0 || suppliers.count() != 0
                || supplierMaterials.count() != 0 || demands.count() != 0 || purchaseOrders.count() != 0
                || stockTransactions.count() != 0 || documents.count() != 0) {
            throw new IllegalStateException("Refusing to seed a non-empty database without a demo dataset marker");
        }
    }

    private LocalDate baselineDate() {
        String configured = properties.getBaselineDate();
        return configured == null || configured.isBlank() ? LocalDate.now(clock) : LocalDate.parse(configured);
    }

    private Map<String, Object> state() {
        return state(false);
    }

    private Map<String, Object> state(boolean forUpdate) {
        String lockClause = forUpdate ? " FOR UPDATE" : "";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dataset_version, baseline_date, status, failure_reason FROM demo_dataset_state WHERE dataset_key = ?"
                        + lockClause,
                DATASET_KEY);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String databaseName(String jdbcUrl) {
        String withoutQuery = jdbcUrl.split("\\?", 2)[0];
        int slash = withoutQuery.lastIndexOf('/');
        return slash < 0 ? "" : withoutQuery.substring(slash + 1);
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }

}

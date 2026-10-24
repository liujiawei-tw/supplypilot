package com.supplypilot.demo;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.supplypilot.demand.repository.DemandRepository;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.dto.response.DocumentResponse;
import com.supplypilot.document.rag.DocumentIngestionService;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.semantic.SemanticStore;
import com.supplypilot.document.service.DocumentService;
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
import com.supplypilot.security.AuthenticatedUser;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private static final Pattern DATASET_VERSION_PATTERN = Pattern.compile("sp-([0-9]+)-(?:[a-z0-9]+-)*v([0-9]+)");
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
    private final DocumentService documentService;
    private final DocumentIngestionService ingestionService;
    private final DocumentStorageService documentStorage;
    private final SemanticStore semanticStore;
    private final SemanticProperties semanticProperties;
    private final Path documentStorageRoot;

    public DemoDatasetCoordinator(
            DemoProperties properties, Clock clock, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, UserRepository users, MaterialRepository materials,
            InventoryRepository inventories, SupplierRepository suppliers,
            SupplierMaterialRepository supplierMaterials, DemandRepository demands,
            PurchaseOrderRepository purchaseOrders, StockTransactionRepository stockTransactions,
            DocumentMetadataRepository documents, DocumentService documentService,
            DocumentIngestionService ingestionService, DocumentStorageService documentStorage,
            SemanticStore semanticStore, SemanticProperties semanticProperties,
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
        this.documentService = documentService;
        this.ingestionService = ingestionService;
        this.documentStorage = documentStorage;
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
        Map<String, Object> state = state();
        if (state != null) {
            ExistingDatasetAction action = classifyExistingDataset(
                    state.get("status"), state.get("dataset_version"), properties.getDatasetVersion());
            switch (action) {
                case ALREADY_COMPLETE -> log.info("DEMO_DATASET_ALREADY_COMPLETE key={} version={} baselineDate={}",
                        DATASET_KEY, state.get("dataset_version"), state.get("baseline_date"));
                case LEGACY_UPDATE_REQUIRED -> log.warn("DEMO_DATASET_UPDATE_REQUIRED key={} currentVersion={} "
                                + "currentStatus=COMPLETE targetVersion={}; legacy records are preserved and no reset "
                                + "or automatic upgrade was performed",
                        DATASET_KEY, state.get("dataset_version"), properties.getDatasetVersion());
                case MANUAL_STEPS_INCOMPLETE -> log.warn("DEMO_DATASET_UPDATE_REQUIRED key={} version={} "
                                + "manualStepsIncomplete=true reason={}; startup seeding preserved all records",
                        DATASET_KEY, state.get("dataset_version"), state.get("failure_reason"));
            }
            return;
        }
        requireEmptyDatabase();
        LocalDate baselineDate = baselineDate();
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO demo_dataset_state
                    (dataset_key, dataset_version, baseline_date, status, created_at)
                VALUES (?, ?, ?, 'INITIALIZING', ?)
                """, DATASET_KEY, properties.getDatasetVersion(), baselineDate, now);
        try {
            transactions.executeWithoutResult(transaction -> seedRelational(baselineDate, now));
            jdbc.update("UPDATE demo_dataset_state SET status = 'RELATIONAL_READY' WHERE dataset_key = ?", DATASET_KEY);
            bootstrapDocuments();
            jdbc.update("""
                    UPDATE demo_dataset_state SET status = 'COMPLETE', completed_at = ?, failure_reason = NULL
                    WHERE dataset_key = ?
                    """, clock.instant(), DATASET_KEY);
            log.info("DEMO_DATASET_COMPLETE key={} version={} baselineDate={}", DATASET_KEY,
                    properties.getDatasetVersion(), baselineDate);
        } catch (RuntimeException exception) {
            String reason = exception.getClass().getSimpleName();
            jdbc.update("UPDATE demo_dataset_state SET status = 'FAILED', failure_reason = ? WHERE dataset_key = ?",
                    reason.substring(0, Math.min(reason.length(), 120)), DATASET_KEY);
            throw exception;
        }
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
            case "UPDATE_REQUIRED" -> {
                if (!version.equals(targetVersion)) {
                    throw new IllegalStateException("UPDATE_REQUIRED demo dataset must use the configured version");
                }
                yield ExistingDatasetAction.MANUAL_STEPS_INCOMPLETE;
            }
            case "INITIALIZING", "RELATIONAL_READY", "FAILED" -> throw new IllegalStateException(
                    "Demo dataset is not complete; refusing to treat status " + status + " as a baseline");
            default -> throw new IllegalStateException("Unknown demo dataset status: " + status);
        };
    }

    private static boolean isLegalDatasetVersion(String version) {
        return version != null && DATASET_VERSION_PATTERN.matcher(version).matches();
    }

    private static boolean isOlderDatasetVersion(String candidate, String target) {
        Matcher candidateMatcher = DATASET_VERSION_PATTERN.matcher(candidate);
        Matcher targetMatcher = DATASET_VERSION_PATTERN.matcher(target);
        if (!candidateMatcher.matches() || !targetMatcher.matches()) return false;
        int revisionOrder = new BigInteger(candidateMatcher.group(1)).compareTo(new BigInteger(targetMatcher.group(1)));
        return revisionOrder < 0 || (revisionOrder == 0
                && new BigInteger(candidateMatcher.group(2)).compareTo(new BigInteger(targetMatcher.group(2))) < 0);
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
            JsonNode root = objectMapper.readTree(path.toFile());
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

    private void bootstrapDocuments() {
        User admin = users.findByUsername("admin").orElseThrow();
        Path root = Path.of(properties.getSamplesPath()).toAbsolutePath().normalize();
        Path catalogPath = root.resolve("corpus-catalog.json");
        if (!Files.isRegularFile(catalogPath)) {
            throw new IllegalStateException("Demo corpus catalog is missing: " + catalogPath);
        }

        try {
            JsonNode catalog = objectMapper.readTree(catalogPath.toFile());
            if (!catalog.path("datasetKey").asText().equals(DATASET_KEY)
                    || !catalog.path("corpusVersion").asText().equals(properties.getDatasetVersion())) {
                throw new IllegalStateException("Demo corpus catalog key/version does not match configured dataset");
            }
            List<JsonNode> seedDocuments = requireValidSeedDocuments(catalog);

            for (JsonNode seed : seedDocuments) {
                String filename = Path.of(seed.path("filename").asText()).getFileName().toString();
                DocumentSeed document = new DocumentSeed(filename,
                        DocumentType.valueOf(seed.path("type").asText()),
                        seed.path("title").asText(), seed.path("version").asText(),
                        seed.path("supplierCodes").isArray() && !seed.path("supplierCodes").isEmpty()
                                ? seed.path("supplierCodes").get(0).asText() : null,
                        seed.path("sourceSha256").asText());
                if (!"REQUIRES_ADMIN_REVIEW".equals(seed.path("firstReviewStatus").asText())
                        || !"SEED_BASELINE".equals(seed.path("classification").asText())) {
                    throw new IllegalStateException("Demo corpus seed must require first admin review");
                }
                if (documents.existsByDocumentTypeAndTitleAndVersion(
                        document.type(), document.title(), document.version())) {
                    throw new IllegalStateException("Unexpected duplicate demo document: " + document.filename());
                }
                Path source = root.resolve(document.filename()).normalize();
                if (!source.startsWith(root) || !Files.isRegularFile(source)) {
                    throw new IllegalStateException("Demo source document is missing: " + document.filename());
                }
                String actualHash = canonicalLfSha256(Files.readString(source, StandardCharsets.UTF_8));
                if (!actualHash.equalsIgnoreCase(document.sourceSha256())) {
                    throw new IllegalStateException("Demo source hash does not match catalog: " + document.filename());
                }
                Long supplierId = document.supplierCode() == null ? null
                        : suppliers.findByCode(document.supplierCode()).orElseThrow().getId();
                DocumentResponse uploaded = withUser(admin, () -> {
                    try {
                        return documentService.upload(document.type(), supplierId, document.title(), document.version(),
                                new DemoMultipartFile(source, "text/plain"));
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException("Cannot read demo source document", exception);
                    }
                });
                DocumentResponse ready = withUser(admin, () -> ingestionService.ingest(uploaded.id()));
                if (ready.status() != DocumentStatus.READY) {
                    throw new IllegalStateException("Demo document ingestion did not become READY: "
                            + document.filename());
                }
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read demo corpus catalog", exception);
        }
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

    private static String canonicalLfSha256(String text) {
        try {
            byte[] canonical = text.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private <T> T withUser(User user, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities()));
            SecurityContextHolder.setContext(context);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
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
                    "ai_purchase_draft_proposals", "document_chunks", "document_metadata",
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
                || documents.count() != 0) {
            throw new IllegalStateException("Refusing to seed a non-empty database without a demo dataset marker");
        }
    }

    private LocalDate baselineDate() {
        String configured = properties.getBaselineDate();
        return configured == null || configured.isBlank() ? LocalDate.now(clock) : LocalDate.parse(configured);
    }

    private Map<String, Object> state() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dataset_version, baseline_date, status, failure_reason FROM demo_dataset_state WHERE dataset_key = ?",
                DATASET_KEY);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String databaseName(String jdbcUrl) {
        String withoutQuery = jdbcUrl.split("\\?", 2)[0];
        int slash = withoutQuery.lastIndexOf('/');
        return slash < 0 ? "" : withoutQuery.substring(slash + 1);
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }

    private record DocumentSeed(String filename, DocumentType type, String title, String version,
                                String supplierCode, String sourceSha256) { }
}

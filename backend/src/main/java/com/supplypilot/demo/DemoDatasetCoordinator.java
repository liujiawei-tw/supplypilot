package com.supplypilot.demo;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.supplypilot.demand.domain.Demand;
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
    private static final Logger log = LoggerFactory.getLogger(DemoDatasetCoordinator.class);

    private final DemoProperties properties;
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
            @Value("${supplypilot.document-storage.path:./data/demo-documents}") String documentStoragePath) {
        this.properties = properties;
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
            if ("COMPLETE".equals(state.get("status"))
                    && properties.getDatasetVersion().equals(state.get("dataset_version"))) {
                log.info("DEMO_DATASET_ALREADY_COMPLETE key={} version={} baselineDate={}", DATASET_KEY,
                        state.get("dataset_version"), state.get("baseline_date"));
                return;
            }
            throw new IllegalStateException("Demo dataset is partial or has a different version; run the guarded reset");
        }
        requireEmptyDatabase();
        requirePassword();

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

    private void seedRelational(LocalDate baselineDate, Instant now) {
        Map<String, User> userMap = seedUsers(now);
        Map<String, Material> materialMap = seedMaterialsAndInventory(now);
        Map<String, com.supplypilot.supplier.domain.Supplier> supplierMap = seedSuppliers(now);
        Map<String, SupplierMaterial> supplierMaterialMap = seedSupplierMaterials(
                supplierMap, materialMap, now);
        seedDemands(materialMap, userMap.get("planner"), baselineDate, now);
        seedPurchaseOrders(materialMap, supplierMap, supplierMaterialMap, userMap, baselineDate, now);
    }

    private Map<String, User> seedUsers(Instant now) {
        String hash = passwordEncoder.encode(properties.getAccountPassword());
        Map<String, User> result = new LinkedHashMap<>();
        result.put("planner", User.create("planner", hash, "Demo Production Planner",
                UserRole.PRODUCTION_PLANNER, now));
        result.put("buyer", User.create("buyer", hash, "Demo Procurement Buyer",
                UserRole.PROCUREMENT_STAFF, now));
        result.put("manager", User.create("manager", hash, "Demo Procurement Manager",
                UserRole.PROCUREMENT_MANAGER, now));
        result.put("warehouse", User.create("warehouse", hash, "Demo Warehouse Staff",
                UserRole.WAREHOUSE_STAFF, now));
        result.put("admin", User.create("admin", hash, "Demo Administrator", UserRole.ADMIN, now));
        users.saveAll(result.values());
        users.flush();
        return result;
    }

    private Map<String, Material> seedMaterialsAndInventory(Instant now) {
        List<MaterialSeed> seeds = List.of(
                new MaterialSeed("MAT-001", "Assam Tea Extract", "200", "300", true),
                new MaterialSeed("MAT-002", "Mango Concentrate", "100", "175", true),
                new MaterialSeed("MAT-003", "Fructose Syrup", "300", "260", true),
                new MaterialSeed("MAT-004", "Milk Tea Base", "150", "150", true),
                new MaterialSeed("MAT-005", "Tapioca Flavor Powder", "50", "0", false),
                new MaterialSeed("MAT-006", "Jasmine Tea Extract", "180", "420", true),
                new MaterialSeed("MAT-007", "Passion Fruit Puree", "90", "70", true),
                new MaterialSeed("MAT-008", "Brown Sugar Syrup", "120", "0", true),
                new MaterialSeed("MAT-009", "Taro Flavor Powder", "60", "55", true),
                new MaterialSeed("MAT-010", "Lemon Juice Concentrate", "80", "200", true),
                new MaterialSeed("MAT-011", "Oolong Tea Extract", "160", "145", true),
                new MaterialSeed("MAT-012", "Coconut Cream Base", "110", "240", true));
        Map<String, Material> result = new LinkedHashMap<>();
        for (MaterialSeed seed : seeds) {
            Material material = Material.create(seed.code(), seed.name(), BaseUnit.KG,
                    decimal(seed.safetyStock()), now);
            if (!seed.active()) material.update(null, null, MaterialStatus.INACTIVE, now);
            materials.save(material);
            Inventory inventory = Inventory.create(material, now);
            inventory.replaceSnapshot(decimal(seed.inventory()), now);
            inventories.save(inventory);
            result.put(seed.code(), material);
        }
        materials.flush();
        inventories.flush();
        return result;
    }

    private Map<String, com.supplypilot.supplier.domain.Supplier> seedSuppliers(Instant now) {
        List<SupplierSeed> seeds = List.of(
                new SupplierSeed("SUP-001", "Formosa Ingredients", "Taiwan", "92", "90", true),
                new SupplierSeed("SUP-002", "East Harbor Trading", "Taiwan", "85", "91", true),
                new SupplierSeed("SUP-003", "Green Leaf Supply", "Taiwan", "88", "86", true),
                new SupplierSeed("SUP-004", "Old Vendor Co.", "Taiwan", "70", "72", false),
                new SupplierSeed("SUP-005", "Pacific Food Materials", "Japan", "94", "93", true),
                new SupplierSeed("SUP-006", "Golden Field Trading", "Thailand", "82", "89", true));
        Map<String, com.supplypilot.supplier.domain.Supplier> result = new LinkedHashMap<>();
        for (SupplierSeed seed : seeds) {
            var supplier = com.supplypilot.supplier.domain.Supplier.create(seed.code(), seed.name(), seed.country(),
                    decimal(seed.reliability()), decimal(seed.quality()), now);
            if (!seed.active()) supplier.update(null, null, null, null, SupplierStatus.INACTIVE, now);
            suppliers.save(supplier);
            result.put(seed.code(), supplier);
        }
        suppliers.flush();
        return result;
    }

    private Map<String, SupplierMaterial> seedSupplierMaterials(
            Map<String, com.supplypilot.supplier.domain.Supplier> supplierMap,
            Map<String, Material> materialMap, Instant now) {
        Map<String, SupplierMaterial> result = new LinkedHashMap<>();
        addSupplierMaterial(result, supplierMap, materialMap, "SUP-001", "MAT-001", "100", "800", "100", 5, true, now);
        addSupplierMaterial(result, supplierMap, materialMap, "SUP-002", "MAT-001", "104", "500", "50", 3, true, now);
        addSupplierMaterial(result, supplierMap, materialMap, "SUP-003", "MAT-001", "97", "1000", "100", 10, true, now);
        addSupplierMaterial(result, supplierMap, materialMap, "SUP-004", "MAT-001", "90", "500", "50", 2, false, now);
        addSupplierMaterial(result, supplierMap, materialMap, "SUP-005", "MAT-001", "102", "600", "100", 7, true, now);
        int sequence = 0;
        for (String supplierCode : List.of("SUP-001", "SUP-002", "SUP-003", "SUP-005", "SUP-006")) {
            for (String materialCode : List.of("MAT-002", "MAT-003", "MAT-004", "MAT-006", "MAT-007")) {
                sequence++;
                addSupplierMaterial(result, supplierMap, materialMap, supplierCode, materialCode,
                        Integer.toString(55 + sequence * 3), Integer.toString(200 + (sequence % 4) * 100),
                        Integer.toString(25 + (sequence % 3) * 25), 2 + sequence % 9, true, now);
            }
        }
        supplierMaterials.flush();
        return result;
    }

    private void addSupplierMaterial(Map<String, SupplierMaterial> result,
                                     Map<String, com.supplypilot.supplier.domain.Supplier> supplierMap,
                                     Map<String, Material> materialMap, String supplierCode, String materialCode,
                                     String price, String moq, String multiple, int leadTime, boolean active, Instant now) {
        SupplierMaterial relation = SupplierMaterial.create(supplierMap.get(supplierCode), materialMap.get(materialCode),
                decimal(price), decimal(moq), decimal(multiple), leadTime, Currency.TWD, now);
        if (!active) relation.update(null, null, null, null, SupplierMaterialStatus.INACTIVE, now);
        supplierMaterials.save(relation);
        result.put(supplierCode + ":" + materialCode, relation);
    }

    private void seedDemands(Map<String, Material> materialMap, User planner, LocalDate date, Instant now) {
        List<DemandSeed> seeds = List.of(
                new DemandSeed("MAT-001", "600", 15), new DemandSeed("MAT-001", "400", 20),
                new DemandSeed("MAT-002", "240", 12), new DemandSeed("MAT-002", "160", 28),
                new DemandSeed("MAT-003", "500", 18), new DemandSeed("MAT-004", "280", 14),
                new DemandSeed("MAT-006", "350", 22), new DemandSeed("MAT-007", "190", 11),
                new DemandSeed("MAT-008", "210", 25), new DemandSeed("MAT-009", "90", 16),
                new DemandSeed("MAT-010", "260", 30), new DemandSeed("MAT-011", "180", 7));
        for (int index = 0; index < seeds.size(); index++) {
            DemandSeed seed = seeds.get(index);
            demands.save(Demand.create(materialMap.get(seed.materialCode()), decimal(seed.quantity()),
                    date.plusDays(seed.daysFromAnchor()), "SP-901 demo demand " + (index + 1), planner, now));
        }
        demands.flush();
    }

    private void seedPurchaseOrders(Map<String, Material> materialMap,
                                    Map<String, com.supplypilot.supplier.domain.Supplier> supplierMap,
                                    Map<String, SupplierMaterial> relationMap, Map<String, User> userMap,
                                    LocalDate date, Instant now) {
        createPo(1, PurchaseOrderStatus.DRAFT, "SUP-001", "MAT-006", "300", date.plusDays(18),
                materialMap, supplierMap, relationMap, userMap, now);
        createPo(2, PurchaseOrderStatus.DRAFT, "SUP-002", "MAT-002", "250", date.plusDays(16),
                materialMap, supplierMap, relationMap, userMap, now);
        createPo(3, PurchaseOrderStatus.APPROVED, "SUP-003", "MAT-003", "400", date.plusDays(13),
                materialMap, supplierMap, relationMap, userMap, now);
        createPo(4, PurchaseOrderStatus.APPROVED, "SUP-005", "MAT-004", "300", date.plusDays(15),
                materialMap, supplierMap, relationMap, userMap, now);
        createPo(5, PurchaseOrderStatus.ORDERED, "SUP-001", "MAT-001", "150", date.plusDays(10),
                materialMap, supplierMap, relationMap, userMap, now);
        createPo(6, PurchaseOrderStatus.ORDERED, "SUP-002", "MAT-007", "225", date.plusDays(20),
                materialMap, supplierMap, relationMap, userMap, now);
        createPo(7, PurchaseOrderStatus.ORDERED, "SUP-005", "MAT-006", "250", date.plusDays(24),
                materialMap, supplierMap, relationMap, userMap, now);
        createPo(8, PurchaseOrderStatus.RECEIVED, "SUP-001", "MAT-002", "25", date.minusDays(2),
                materialMap, supplierMap, relationMap, userMap, now);
        createPo(9, PurchaseOrderStatus.RECEIVED, "SUP-003", "MAT-003", "40", date.minusDays(1),
                materialMap, supplierMap, relationMap, userMap, now);
        createPo(10, PurchaseOrderStatus.REJECTED, "SUP-006", "MAT-007", "200", date.plusDays(19),
                materialMap, supplierMap, relationMap, userMap, now);
    }

    private void createPo(int sequence, PurchaseOrderStatus target, String supplierCode, String materialCode,
                          String quantity, LocalDate deliveryDate, Map<String, Material> materialMap,
                          Map<String, com.supplypilot.supplier.domain.Supplier> supplierMap,
                          Map<String, SupplierMaterial> relationMap, Map<String, User> userMap, Instant now) {
        Material material = materialMap.get(materialCode);
        PurchaseOrder order = PurchaseOrder.create(supplierMap.get(supplierCode), deliveryDate, userMap.get("buyer"), now);
        order.assignPendingPoNumber("DEMO-PENDING-" + sequence);
        order.addItem(PurchaseOrderItem.create(material, decimal(quantity),
                relationMap.get(supplierCode + ":" + materialCode).getUnitPrice(), now));
        purchaseOrders.saveAndFlush(order);
        order.assignDisplayPoNumber("PO-%04d%02d%02d-%06d".formatted(deliveryDate.getYear(),
                deliveryDate.getMonthValue(), deliveryDate.getDayOfMonth(), order.getId()));
        if (target == PurchaseOrderStatus.REJECTED) {
            order.reject("Demo rejection: delivery plan changed", userMap.get("manager"), now);
        } else if (target != PurchaseOrderStatus.DRAFT) {
            order.approve(userMap.get("manager"), now);
            if (target == PurchaseOrderStatus.ORDERED || target == PurchaseOrderStatus.RECEIVED) {
                order.markOrdered(userMap.get("buyer"), now);
            }
            if (target == PurchaseOrderStatus.RECEIVED) {
                Inventory inventory = inventories.findByMaterialId(material.getId()).orElseThrow();
                inventory.receive(decimal(quantity), now);
                inventories.save(inventory);
                stockTransactions.save(StockTransaction.purchaseReceive(
                        material, decimal(quantity), order.getId(), userMap.get("warehouse"), now));
                order.receive(userMap.get("warehouse"), now);
            }
        }
        purchaseOrders.saveAndFlush(order);
    }

    private void bootstrapDocuments() {
        User admin = users.findByUsername("admin").orElseThrow();
        List<DocumentSeed> seeds = List.of(
                new DocumentSeed("supplier-contract-sup-001-v1.txt", DocumentType.SUPPLIER_CONTRACT,
                        "SUP-001 Delivery and Responsibility Agreement", "1.0", "SUP-001"),
                new DocumentSeed("quotation-sup-002-v1.txt", DocumentType.QUOTATION,
                        "SUP-002 Fictional Quotation Terms", "1.0", "SUP-002"),
                new DocumentSeed("procurement-sop-v1.txt", DocumentType.PROCUREMENT_SOP,
                        "SupplyPilot Demo Procurement SOP", "1.0", null),
                new DocumentSeed("product-specification-mat-001-v1.txt", DocumentType.PRODUCT_SPECIFICATION,
                        "MAT-001 Assam Tea Extract Specification", "1.0", "SUP-001"),
                new DocumentSeed("quality-requirement-v1.txt", DocumentType.QUALITY_REQUIREMENT,
                        "Demo Ingredient Quality Requirements", "1.0", "SUP-003"));
        Path root = Path.of(properties.getSamplesPath()).toAbsolutePath().normalize();
        for (DocumentSeed seed : seeds) {
            if (documents.existsByDocumentTypeAndTitleAndVersion(seed.type(), seed.title(), seed.version())) {
                throw new IllegalStateException("Unexpected duplicate demo document: " + seed.filename());
            }
            Path source = root.resolve(seed.filename()).normalize();
            if (!source.startsWith(root) || !Files.isRegularFile(source)) {
                throw new IllegalStateException("Demo source document is missing: " + seed.filename());
            }
            Long supplierId = seed.supplierCode() == null ? null
                    : suppliers.findByCode(seed.supplierCode()).orElseThrow().getId();
            DocumentResponse uploaded = withUser(admin, () -> {
                try {
                    return documentService.upload(seed.type(), supplierId, seed.title(), seed.version(),
                            new DemoMultipartFile(source, "text/plain"));
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Cannot read demo source document", exception);
                }
            });
            DocumentResponse ready = withUser(admin, () -> ingestionService.ingest(uploaded.id()));
            if (ready.status() != DocumentStatus.READY) {
                throw new IllegalStateException("Demo document ingestion did not become READY: " + seed.filename());
            }
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

    private void requirePassword() {
        String password = properties.getAccountPassword();
        if (password == null || password.isBlank() || password.length() < 12) {
            throw new IllegalStateException("DEMO_ACCOUNT_PASSWORD must contain at least 12 characters");
        }
    }

    private LocalDate baselineDate() {
        String configured = properties.getBaselineDate();
        return configured == null || configured.isBlank() ? LocalDate.now(clock) : LocalDate.parse(configured);
    }

    private Map<String, Object> state() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dataset_version, baseline_date, status FROM demo_dataset_state WHERE dataset_key = ?",
                DATASET_KEY);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String databaseName(String jdbcUrl) {
        String withoutQuery = jdbcUrl.split("\\?", 2)[0];
        int slash = withoutQuery.lastIndexOf('/');
        return slash < 0 ? "" : withoutQuery.substring(slash + 1);
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }

    private record MaterialSeed(String code, String name, String safetyStock, String inventory, boolean active) { }
    private record SupplierSeed(String code, String name, String country, String reliability, String quality,
                                boolean active) { }
    private record DemandSeed(String materialCode, String quantity, int daysFromAnchor) { }
    private record DocumentSeed(String filename, DocumentType type, String title, String version,
                                String supplierCode) { }
}

package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class PurchaseOrderControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private SupplierMaterialRepository supplierMaterialRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private JwtTokenService tokens;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tokens = new JwtTokenService(jwtProperties, clock);
    }

    @Test
    void deletingPoOwnerPreservesHistoricalSnapshotAndAllowsClaim() throws Exception {
        Supplier supplier = activeSupplier();
        Material material = activeMaterial();
        activeSupplierMaterial(supplier, material);
        User owner = user(UserRole.ADMIN);
        User manager = userWithManagement();
        User nextOwner = user(UserRole.ADMIN);

        MvcResult created = create(owner, payload(supplier, List.of(item(material, 2, 10))))
                .andExpect(status().isCreated()).andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(delete("/api/v1/users/{id}", owner.getId()).header("Authorization", bearer(manager)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/purchase-orders/{id}", id).header("Authorization", bearer(owner)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/purchase-orders/{id}", id).header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.assignedTo").doesNotExist())
                .andExpect(jsonPath("$.createdBy").value(owner.getId()))
                .andExpect(jsonPath("$.createdByDisplayName").value(owner.getRole().name()));

        mockMvc.perform(post("/api/v1/purchase-orders/{id}/claim", id).header("Authorization", bearer(nextOwner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignedTo").value(nextOwner.getId()));
    }

    private User userWithManagement() {
        return userRepository.saveAndFlush(User.create("manager-" + suffix(), "hash", "採購管理者",
                UserRole.ADMIN, true, clock.instant()));
    }

    @Test
    void procurementStaffAndAdminCreateDraftAndGetIt() throws Exception {
        Supplier supplier = activeSupplier();
        Material first = activeMaterial();
        Material second = activeMaterial();
        activeSupplierMaterial(supplier, first);
        activeSupplierMaterial(supplier, second);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        User admin = user(UserRole.ADMIN);

        MvcResult staffResult = create(staff, payload(supplier, List.of(item(first, 2, 0), item(second, 3, 12)))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].unit").value("KG"))
                .andExpect(jsonPath("$.createdBy").value(staff.getId())).andReturn();
        create(admin, payload(supplier, List.of(item(first, 1, 1)))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"));

        long id = ((Number) com.jayway.jsonpath.JsonPath.read(staffResult.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(get("/api/v1/purchase-orders/{id}", id).header("Authorization", bearer(staff)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void rejectsUnauthorizedRolesAndClientOwnedFields() throws Exception {
        Supplier supplier = activeSupplier();
        Material material = activeMaterial();
        activeSupplierMaterial(supplier, material);
        String valid = payload(supplier, List.of(item(material, 1, 0)));
        User planner = user(UserRole.PRODUCTION_PLANNER);

        mockMvc.perform(post("/api/v1/purchase-orders").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        create(planner, valid).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        create(user(UserRole.PROCUREMENT_STAFF), valid.substring(0, valid.length() - 1) + ",\"status\":\"APPROVED\",\"createdBy\":999,\"poNumber\":\"CLIENT\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create(user(UserRole.PROCUREMENT_STAFF), "{\"supplierId\":" + supplier.getId() + ",\"items\":[{\"materialId\":" + material.getId() + ",\"quantity\":1,\"unitPrice\":0,\"unit\":\"PCS\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create(user(UserRole.PROCUREMENT_STAFF), "{\"supplierId\":" + supplier.getId() + ",\"items\":[{\"materialId\":" + material.getId() + ",\"quantity\":1,\"unitPrice\":0,\"recommendedSupplierIdSnapshot\":999,\"recommendedQuantitySnapshot\":999}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void validatesSupplierMaterialRelationshipAndValues() throws Exception {
        User staff = user(UserRole.PROCUREMENT_STAFF);
        create(staff, "{\"supplierId\":999999999,\"items\":[]}").andExpect(status().isBadRequest());
        create(staff, "{\"supplierId\":999999999,\"items\":[{\"materialId\":999999999,\"quantity\":1,\"unitPrice\":0}]}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
        Supplier inactiveSupplier = activeSupplier();
        inactiveSupplier.update(null, null, null, null, SupplierStatus.INACTIVE, clock.instant());
        supplierRepository.saveAndFlush(inactiveSupplier);
        Material active = activeMaterial();
        create(staff, payload(inactiveSupplier, List.of(item(active, 1, 0)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));

        Supplier supplier = activeSupplier();
        Material inactiveMaterial = activeMaterial();
        inactiveMaterial.update(null, null, MaterialStatus.INACTIVE, clock.instant());
        materialRepository.saveAndFlush(inactiveMaterial);
        activeSupplierMaterial(supplier, inactiveMaterial);
        create(staff, payload(supplier, List.of(item(inactiveMaterial, 1, 0)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MATERIAL_INACTIVE"));

        Material absentRelationship = activeMaterial();
        create(staff, payload(supplier, List.of(item(absentRelationship, 1, 0)))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_MATERIAL_NOT_FOUND"));
        Material inactiveRelationship = activeMaterial();
        SupplierMaterial relation = activeSupplierMaterial(supplier, inactiveRelationship);
        relation.update(null, null, null, null, SupplierMaterialStatus.INACTIVE, clock.instant());
        supplierMaterialRepository.saveAndFlush(relation);
        create(staff, payload(supplier, List.of(item(inactiveRelationship, 1, 0)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUPPLIER_MATERIAL_INACTIVE"));
        create(staff, payload(supplier, List.of(item(absentRelationship, 0, 0)))).andExpect(status().isBadRequest());
        create(staff, payload(supplier, List.of(item(absentRelationship, -1, 0)))).andExpect(status().isBadRequest());
        create(staff, payload(supplier, List.of(item(absentRelationship, 1, -1)))).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateMaterialAndRollsBackWholeAggregateWhenAnyItemFails() throws Exception {
        Supplier supplier = activeSupplier();
        Material first = activeMaterial();
        activeSupplierMaterial(supplier, first);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        long before = purchaseOrderRepository.count();

        create(staff, payload(supplier, List.of(item(first, 1, 0), item(first, 2, 0))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_MATERIAL"));
        assertThat(purchaseOrderRepository.count()).isEqualTo(before);

        Material second = activeMaterial();
        activeSupplierMaterial(supplier, second);
        create(staff, payload(supplier, List.of(item(first, 1, 0), "{\"materialId\":999999999,\"quantity\":1,\"unitPrice\":0}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MATERIAL_NOT_FOUND"));
        assertThat(purchaseOrderRepository.count()).isEqualTo(before);
    }

    @Test
    void rollsBackPersistedHeaderAndItemsWhenDatabaseRejectsSecondItemInsert() throws Exception {
        Supplier supplier = activeSupplier();
        Material first = activeMaterial();
        Material rejectedByDatabase = activeMaterial();
        activeSupplierMaterial(supplier, first);
        activeSupplierMaterial(supplier, rejectedByDatabase);
        User staff = user(UserRole.PROCUREMENT_STAFF);

        addTestOnlyMaterialConstraint(rejectedByDatabase.getId());
        try {
            create(staff, payload(supplier, List.of(item(first, 1, 0), item(rejectedByDatabase, 2, 0))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"));
        } finally {
            removeTestOnlyMaterialConstraint();
        }

        // JdbcTemplate uses a separate JDBC transaction and cannot read the service transaction's persistence context.
        Long headerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_orders WHERE supplier_id = ? AND created_by = ?",
                Long.class, supplier.getId(), staff.getId());
        Long itemCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM purchase_order_items item
                JOIN purchase_orders purchase_order ON purchase_order.id = item.purchase_order_id
                WHERE purchase_order.supplier_id = ? AND purchase_order.created_by = ?
                """, Long.class, supplier.getId(), staff.getId());
        assertThat(headerCount).isZero();
        assertThat(itemCount).isZero();
    }

    @Test
    void permitsManualPoButRejectsIncompleteRecommendationInputs() throws Exception {
        Supplier supplier = activeSupplier();
        Material material = activeMaterial();
        activeSupplierMaterial(supplier, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        create(staff, payload(supplier, List.of(item(material, 1, 0)))).andExpect(status().isCreated());
        create(staff, "{\"supplierId\":" + supplier.getId() + ",\"items\":[{\"materialId\":" + material.getId() + ",\"quantity\":1,\"unitPrice\":0,\"recommendationId\":123}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SUPPLIER_RECOMMENDATION_REFERENCE"));
        create(staff, "{\"supplierId\":" + supplier.getId() + ",\"items\":[{\"materialId\":" + material.getId() + ",\"quantity\":1,\"unitPrice\":0,\"supplierOverrideReason\":\"manual\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RECOMMENDATION_REFERENCE"));
    }

    @Test
    void concurrentDraftCreationProducesUniqueDisplayNumbers() throws Exception {
        Supplier supplier = activeSupplier();
        Material material = activeMaterial();
        activeSupplierMaterial(supplier, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        String body = payload(supplier, List.of(item(material, 1, 0)));
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<String>> requests = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                requests.add(() -> mockMvc.perform(post("/api/v1/purchase-orders").header("Authorization", bearer(staff))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn()
                        .getResponse().getContentAsString());
            }
            List<Future<String>> results = executor.invokeAll(requests);
            List<String> poNumbers = new ArrayList<>();
            for (Future<String> result : results) poNumbers.add(com.jayway.jsonpath.JsonPath.read(result.get(), "$.poNumber"));
            assertThat(poNumbers).doesNotHaveDuplicates();
            assertThat(purchaseOrderRepository.findAll().stream().map(order -> order.getPoNumber())).containsAll(poNumbers);
        } finally {
            executor.shutdownNow();
        }
    }

    private org.springframework.test.web.servlet.ResultActions create(User user, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/purchase-orders").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(payload));
    }

    private String payload(Supplier supplier, List<String> items) {
        return "{\"supplierId\":" + supplier.getId() + ",\"items\":[" + String.join(",", items) + "]}";
    }

    private String item(Material material, int quantity, int unitPrice) {
        return "{\"materialId\":" + material.getId() + ",\"quantity\":" + quantity + ",\"unitPrice\":" + unitPrice + "}";
    }

    private Supplier activeSupplier() {
        return supplierRepository.saveAndFlush(Supplier.create("SUP-PO-" + suffix(), "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
    }

    private Material activeMaterial() {
        return materialRepository.saveAndFlush(Material.create("MAT-PO-" + suffix(), "Material", BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
    }

    private SupplierMaterial activeSupplierMaterial(Supplier supplier, Material material) {
        return supplierMaterialRepository.saveAndFlush(SupplierMaterial.create(supplier, material, BigDecimal.ZERO, BigDecimal.ONE, null, 0, Currency.TWD, clock.instant()));
    }

    private User user(UserRole role) {
        return userRepository.saveAndFlush(User.create("po-" + suffix(), "hash", role.name(), role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private void addTestOnlyMaterialConstraint(Long materialId) {
        jdbcTemplate.execute("""
                ALTER TABLE purchase_order_items
                ADD CONSTRAINT chk_sp600_forced_item_failure
                CHECK (material_id <> %d)
                """.formatted(materialId));
    }

    private void removeTestOnlyMaterialConstraint() {
        jdbcTemplate.execute("ALTER TABLE purchase_order_items DROP CHECK chk_sp600_forced_item_failure");
        Long constraintCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'purchase_order_items'
                  AND constraint_name = 'chk_sp600_forced_item_failure'
                """, Long.class);
        assertThat(constraintCount).isZero();
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.recommendation.domain.PurchaseRecommendation;
import com.supplypilot.recommendation.domain.RecommendationOutcome;
import com.supplypilot.recommendation.domain.SupplierRecommendationResult;
import com.supplypilot.recommendation.repository.PurchaseRecommendationRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class PurchaseOrderUpdateIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private SupplierMaterialRepository supplierMaterialRepository;
    @Autowired private PurchaseRecommendationRepository recommendationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private JwtTokenService tokens;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tokens = new JwtTokenService(jwtProperties, clock);
    }

    @Test
    void updatesDraftFieldsAndReconcilesItemsWithoutReplacingHeaderOrUnchangedItemIdentity() throws Exception {
        Supplier originalSupplier = supplier();
        Supplier replacementSupplier = supplier();
        Material retained = material();
        Material removed = material();
        Material added = material();
        supplies(originalSupplier, retained);
        supplies(originalSupplier, removed);
        supplies(replacementSupplier, retained);
        supplies(replacementSupplier, removed);
        supplies(replacementSupplier, added);
        User staff = user(UserRole.PROCUREMENT_STAFF);

        MvcResult created = create(staff, originalSupplier,
                manualItem(retained, "2", "10"), manualItem(removed, "3", "20"))
                .andExpect(status().isCreated()).andReturn();
        String body = created.getResponse().getContentAsString();
        long id = number(body, "$.id");
        long retainedItemId = number(body, "$.items[0].id");
        String poNumber = JsonPath.read(body, "$.poNumber");
        String createdAt = JsonPath.read(body, "$.createdAt");
        long createdBy = number(body, "$.createdBy");

        update(staff, id, "{\"expectedDeliveryDate\":\"2026-10-30\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").value(originalSupplier.getId()))
                .andExpect(jsonPath("$.items.length()").value(2));
        update(staff, id, "{\"supplierId\":" + replacementSupplier.getId() + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").value(replacementSupplier.getId()))
                .andExpect(jsonPath("$.expectedDeliveryDate").value("2026-10-30"))
                .andExpect(jsonPath("$.items.length()").value(2));
        update(staff, id, "{\"items\":[" + manualItem(retained, "5", "12") + ","
                + manualItem(added, "7", "14") + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(retainedItemId))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.items[0].unitPrice").value(12));
        update(staff, id, "{\"expectedDeliveryDate\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedDeliveryDate").doesNotExist())
                .andExpect(jsonPath("$.poNumber").value(poNumber))
                .andExpect(jsonPath("$.createdBy").value(createdBy))
                .andExpect(jsonPath("$.createdAt").value(createdAt));
        User manager = user(UserRole.PROCUREMENT_MANAGER);
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/approve", id)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPECTED_DELIVERY_DATE_REQUIRED"));
        mockMvc.perform(get("/api/v1/purchase-orders/{id}", id).header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").value(replacementSupplier.getId()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(retainedItemId))
                .andExpect(jsonPath("$.items[0].quantity").value(5));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_order_items WHERE purchase_order_id = ? AND material_id = ?",
                Long.class, id, removed.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_order_items WHERE purchase_order_id = ?",
                Long.class, id)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM purchase_orders WHERE id = ?", Long.class, id)).isGreaterThanOrEqualTo(4L);
    }

    @Test
    void rejectsInvalidCollectionsMaterialsAndSupplierRelationshipsWithoutPartialUpdate() throws Exception {
        Supplier supplier = supplier();
        Material original = material();
        supplies(supplier, original);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        long id = createdId(create(staff, supplier, manualItem(original, "2", "10")).andReturn());

        update(staff, id, "{\"items\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        update(staff, id, "{\"items\":[" + manualItem(original, "3", "10") + ","
                + manualItem(original, "4", "10") + "]}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MATERIAL"));
        update(staff, id, "{\"items\":[{\"materialId\":999999999,\"quantity\":1,\"unitPrice\":0}]}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MATERIAL_NOT_FOUND"));

        Material inactive = material();
        inactive.update(null, null, MaterialStatus.INACTIVE, clock.instant());
        materialRepository.saveAndFlush(inactive);
        supplies(supplier, inactive);
        update(staff, id, "{\"items\":[" + manualItem(inactive, "1", "0") + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MATERIAL_INACTIVE"));

        Material noRelationship = material();
        update(staff, id, "{\"items\":[" + manualItem(noRelationship, "1", "0") + "]}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_MATERIAL_NOT_FOUND"));
        Material inactiveRelationship = material();
        SupplierMaterial relation = supplies(supplier, inactiveRelationship);
        relation.update(null, null, null, null, SupplierMaterialStatus.INACTIVE, clock.instant());
        supplierMaterialRepository.saveAndFlush(relation);
        update(staff, id, "{\"items\":[" + manualItem(inactiveRelationship, "1", "0") + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUPPLIER_MATERIAL_INACTIVE"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM purchase_order_items WHERE purchase_order_id = ?",
                BigDecimal.class, id)).isEqualByComparingTo("2");
    }

    @Test
    void changingSupplierRevalidatesRetainedItemsAndRollsBackHeaderWhenRelationshipIsInvalid() throws Exception {
        Supplier original = supplier();
        Supplier invalidReplacement = supplier();
        Material material = material();
        supplies(original, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        long id = createdId(create(staff, original, manualItem(material, "2", "10")).andReturn());

        update(staff, id, "{\"supplierId\":" + invalidReplacement.getId() + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_MATERIAL_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT supplier_id FROM purchase_orders WHERE id = ?", Long.class, id)).isEqualTo(original.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM purchase_order_items WHERE purchase_order_id = ?",
                BigDecimal.class, id)).isEqualByComparingTo("2");
    }

    @Test
    void preservesHistoricalRecommendationEvidenceWhenEditingCommercialDraftFields() throws Exception {
        Supplier supplier = supplier();
        Supplier otherSupplier = supplier();
        Material material = material();
        supplies(supplier, material);
        supplies(otherSupplier, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        RecommendationFixture fixture = recommendation(material, supplier, otherSupplier, staff);
        long id = createdId(create(staff, supplier, recommendedItem(material, "10", fixture, null)).andReturn());

        update(staff, id, "{\"items\":[" + manualItem(material, "12", "7") + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(12))
                .andExpect(jsonPath("$.items[0].unitPrice").value(7))
                .andExpect(jsonPath("$.items[0].recommendationId").value(fixture.recommendation().getId()))
                .andExpect(jsonPath("$.items[0].supplierRecommendationResultId").value(fixture.result().getId()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT recommendation_id FROM purchase_order_items WHERE purchase_order_id = ?",
                Long.class, id)).isEqualTo(fixture.recommendation().getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT recommended_quantity_snapshot FROM purchase_order_items WHERE purchase_order_id = ?",
                BigDecimal.class, id)).isEqualByComparingTo("10");

        update(staff, id, "{\"supplierId\":" + otherSupplier.getId() + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.supplierId").value(otherSupplier.getId()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT supplier_id FROM purchase_orders WHERE id = ?", Long.class, id)).isEqualTo(otherSupplier.getId());
    }

    @Test
    void rejectsProtectedFieldsNonDraftStateAndUnauthorizedCallers() throws Exception {
        Supplier supplier = supplier();
        Material material = material();
        supplies(supplier, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        User planner = user(UserRole.PRODUCTION_PLANNER);
        long id = createdId(create(staff, supplier, manualItem(material, "2", "10")).andReturn());

        mockMvc.perform(patch("/api/v1/purchase-orders/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        update(planner, id, "{}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        update(staff, id, "{\"status\":\"APPROVED\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        update(staff, id, "{\"id\":999,\"poNumber\":\"CLIENT\",\"createdBy\":999,\"version\":99}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        update(staff, id, "{\"items\":[{\"materialId\":" + material.getId()
                + ",\"quantity\":2,\"unitPrice\":10,\"recommendedSupplierIdSnapshot\":999}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        jdbcTemplate.update("UPDATE purchase_orders SET status = 'APPROVED' WHERE id = ?", id);
        update(staff, id, "{\"expectedDeliveryDate\":\"2026-11-01\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PO_NOT_EDITABLE"));
    }

    @Test
    void databaseFailureRollsBackExistingItemUpdateAndNewItemInsert() throws Exception {
        Supplier supplier = supplier();
        Material existing = material();
        Material rejected = material();
        supplies(supplier, existing);
        supplies(supplier, rejected);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        long id = createdId(create(staff, supplier, manualItem(existing, "2", "10")).andReturn());

        jdbcTemplate.execute("ALTER TABLE purchase_order_items ADD CONSTRAINT chk_sp602_forced_item_failure CHECK (material_id <> "
                + rejected.getId() + ")");
        try {
            update(staff, id, "{\"items\":[" + manualItem(existing, "99", "50") + ","
                    + manualItem(rejected, "1", "1") + "]}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE purchase_order_items DROP CHECK chk_sp602_forced_item_failure");
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM purchase_order_items WHERE purchase_order_id = ? AND material_id = ?",
                BigDecimal.class, id, existing.getId())).isEqualByComparingTo("2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_order_items WHERE purchase_order_id = ?",
                Long.class, id)).isOne();
    }

    private org.springframework.test.web.servlet.ResultActions create(
            User user, Supplier supplier, String... items) throws Exception {
        return mockMvc.perform(post("/api/v1/purchase-orders")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":" + supplier.getId() + ",\"items\":[" + String.join(",", items) + "]}"));
    }

    private org.springframework.test.web.servlet.ResultActions update(User user, long id, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/purchase-orders/{id}", id)
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long createdId(MvcResult result) throws Exception {
        return number(result.getResponse().getContentAsString(), "$.id");
    }

    private long number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private String manualItem(Material material, String quantity, String price) {
        return "{\"materialId\":" + material.getId() + ",\"quantity\":" + quantity
                + ",\"unitPrice\":" + price + "}";
    }

    private String recommendedItem(Material material, String quantity, RecommendationFixture fixture, String reason) {
        return "{\"materialId\":" + material.getId() + ",\"quantity\":" + quantity + ",\"unitPrice\":5"
                + ",\"recommendationId\":" + fixture.recommendation().getId()
                + ",\"supplierRecommendationResultId\":" + fixture.result().getId()
                + (reason == null ? "" : ",\"quantityOverrideReason\":\"" + reason + "\"") + "}";
    }

    private RecommendationFixture recommendation(
            Material material, Supplier recommended, Supplier alternate, User creator) {
        PurchaseRecommendation recommendation = PurchaseRecommendation.create(
                material, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                RecommendationOutcome.SUPPLIER_RECOMMENDED, recommended, BigDecimal.TEN,
                new BigDecimal("90"), creator, clock.instant());
        SupplierRecommendationResult result = result(recommendation, recommended, 1);
        recommendation.addSupplierResult(result);
        SupplierRecommendationResult alternateResult = null;
        if (alternate != null) {
            alternateResult = result(recommendation, alternate, 2);
            recommendation.addSupplierResult(alternateResult);
        }
        recommendationRepository.saveAndFlush(recommendation);
        return new RecommendationFixture(recommendation, result, alternateResult);
    }

    private SupplierRecommendationResult result(PurchaseRecommendation recommendation, Supplier supplier, int rank) {
        return SupplierRecommendationResult.qualified(
                recommendation, supplier, BigDecimal.TEN, new BigDecimal("5"), new BigDecimal("50"), 1,
                new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("90"),
                new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("90"), rank);
    }

    private Supplier supplier() {
        return supplierRepository.saveAndFlush(Supplier.create(
                "SUP-SP602-" + suffix(), "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
    }

    private Material material() {
        return materialRepository.saveAndFlush(Material.create(
                "MAT-SP602-" + suffix(), "Material", BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
    }

    private SupplierMaterial supplies(Supplier supplier, Material material) {
        return supplierMaterialRepository.saveAndFlush(SupplierMaterial.create(
                supplier, material, BigDecimal.ZERO, BigDecimal.ONE, null, 0, Currency.TWD, clock.instant()));
    }

    private User user(UserRole role) {
        return userRepository.saveAndFlush(User.create(
                "sp602-" + suffix(), "hash", role.name(), role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record RecommendationFixture(
            PurchaseRecommendation recommendation,
            SupplierRecommendationResult result,
            SupplierRecommendationResult alternateResult) { }
}

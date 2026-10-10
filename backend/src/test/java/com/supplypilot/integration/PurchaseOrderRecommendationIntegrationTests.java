package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.recommendation.domain.PurchaseRecommendation;
import com.supplypilot.recommendation.domain.DisqualificationReason;
import com.supplypilot.recommendation.domain.RecommendationOutcome;
import com.supplypilot.recommendation.domain.SupplierRecommendationResult;
import com.supplypilot.recommendation.repository.PurchaseRecommendationRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class PurchaseOrderRecommendationIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private SupplierMaterialRepository supplierMaterialRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PurchaseRecommendationRepository recommendationRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
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
    void createsRecommendationDraftFromPersistedSnapshotAndReturnsEvidence() throws Exception {
        Material material = material();
        Supplier supplier = supplier("RECOMMENDED");
        supplies(supplier, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        RecommendationFixture fixture = recommendation(material, supplier, "200.0000", staff);

        MvcResult result = create(staff, payload(supplier,
                item(material, "200", fixture.recommendation().getId(), fixture.result().getId(), null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].recommendationId").value(fixture.recommendation().getId()))
                .andExpect(jsonPath("$.items[0].supplierRecommendationResultId").value(fixture.result().getId()))
                .andExpect(jsonPath("$.items[0].supplierOverride").value(false))
                .andExpect(jsonPath("$.items[0].quantityOverride").value(false))
                .andExpect(jsonPath("$.items[0].supplierOverrideReason").doesNotExist())
                .andExpect(jsonPath("$.items[0].quantityOverrideReason").doesNotExist())
                .andReturn();
        long purchaseOrderId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT recommended_supplier_id_snapshot
                FROM purchase_order_items WHERE purchase_order_id = ?
                """, Long.class, purchaseOrderId)).isEqualTo(supplier.getId());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT recommended_quantity_snapshot
                FROM purchase_order_items WHERE purchase_order_id = ?
                """, BigDecimal.class, purchaseOrderId)).isEqualByComparingTo("200.0000");
        mockMvc.perform(get("/api/v1/purchase-orders/{id}", purchaseOrderId)
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationId").value(fixture.recommendation().getId()))
                .andExpect(jsonPath("$.items[0].supplierOverrideReason").doesNotExist())
                .andExpect(jsonPath("$.items[0].quantityOverrideReason").doesNotExist());
    }

    @Test
    void persistsSupplierOverrideReasonAndReturnsItFromGet() throws Exception {
        Material material = material();
        Supplier recommended = supplier("RECOMMENDED");
        Supplier selected = supplier("SELECTED");
        supplies(selected, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        RecommendationFixture fixture = recommendation(material, recommended, "200", staff, selected);

        MvcResult result = create(staff, payload(selected,
                item(material, "200", fixture.recommendation().getId(), fixture.alternateResult().getId(),
                        "Urgent capacity allocation", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].supplierOverride").value(true))
                .andExpect(jsonPath("$.items[0].supplierOverrideReason").value("Urgent capacity allocation"))
                .andReturn();
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(get("/api/v1/purchase-orders/{id}", id).header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].supplierOverrideReason").value("Urgent capacity allocation"));
    }

    @Test
    void rejectsSupplierOverrideWithoutNonBlankReasonAndRollsBack() throws Exception {
        Material material = material();
        Supplier recommended = supplier("RECOMMENDED");
        Supplier selected = supplier("SELECTED");
        supplies(selected, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        RecommendationFixture fixture = recommendation(material, recommended, "200", staff, selected);
        long before = purchaseOrderRepository.count();

        create(staff, payload(selected, item(material, "200", fixture.recommendation().getId(),
                fixture.alternateResult().getId(), null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_OVERRIDE_REASON_REQUIRED"));
        create(staff, payload(selected, item(material, "200", fixture.recommendation().getId(),
                fixture.alternateResult().getId(), "   ", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_OVERRIDE_REASON_REQUIRED"));
        assertThat(purchaseOrderRepository.count()).isEqualTo(before);
    }

    @Test
    void persistsQuantityOverrideReasonAndUsesBigDecimalSemanticEquality() throws Exception {
        Material material = material();
        Supplier supplier = supplier("RECOMMENDED");
        supplies(supplier, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        RecommendationFixture fixture = recommendation(material, supplier, "200.0000", staff);

        MvcResult overridden = create(staff, payload(supplier,
                item(material, "250", fixture.recommendation().getId(), fixture.result().getId(), null,
                        "Demand increased")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].quantityOverride").value(true))
                .andExpect(jsonPath("$.items[0].quantityOverrideReason").value("Demand increased"))
                .andReturn();
        long overriddenId = ((Number) JsonPath.read(overridden.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(get("/api/v1/purchase-orders/{id}", overriddenId).header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantityOverrideReason").value("Demand increased"));

        create(staff, payload(supplier,
                item(material, "200", fixture.recommendation().getId(), fixture.result().getId(), null,
                        "must be ignored")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].quantityOverride").value(false))
                .andExpect(jsonPath("$.items[0].quantityOverrideReason").doesNotExist());
    }

    @Test
    void rejectsQuantityOverrideWithoutNonBlankReasonAndRollsBack() throws Exception {
        Material material = material();
        Supplier supplier = supplier("RECOMMENDED");
        supplies(supplier, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        RecommendationFixture fixture = recommendation(material, supplier, "200", staff);
        long before = purchaseOrderRepository.count();

        create(staff, payload(supplier,
                item(material, "250", fixture.recommendation().getId(), fixture.result().getId(), null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUANTITY_OVERRIDE_REASON_REQUIRED"));
        create(staff, payload(supplier,
                item(material, "250", fixture.recommendation().getId(), fixture.result().getId(), null, "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUANTITY_OVERRIDE_REASON_REQUIRED"));
        assertThat(purchaseOrderRepository.count()).isEqualTo(before);
    }

    @Test
    void supportsBothOverridesAndRequiresBothReasons() throws Exception {
        Material material = material();
        Supplier recommended = supplier("RECOMMENDED");
        Supplier selected = supplier("SELECTED");
        supplies(selected, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        RecommendationFixture fixture = recommendation(material, recommended, "200", staff, selected);

        create(staff, payload(selected,
                item(material, "250", fixture.recommendation().getId(), fixture.alternateResult().getId(),
                        "Alternate source", "Demand increased")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].supplierOverrideReason").value("Alternate source"))
                .andExpect(jsonPath("$.items[0].quantityOverrideReason").value("Demand increased"));
        create(staff, payload(selected,
                item(material, "250", fixture.recommendation().getId(), fixture.alternateResult().getId(),
                        "Alternate source", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUANTITY_OVERRIDE_REASON_REQUIRED"));
    }

    @Test
    void doesNotImposeAnUndocumentedRecommendationOutcomeRestriction() throws Exception {
        Material material = material();
        Supplier selected = supplier("SELECTED");
        supplies(selected, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        RecommendationFixture fixture = noQualifiedSupplierRecommendation(material, selected, staff);

        MvcResult result = create(staff, payload(selected,
                item(material, "25", fixture.recommendation().getId(), fixture.result().getId(),
                        "Manual supplier selection", "Manual quantity selection")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].supplierOverride").value(true))
                .andExpect(jsonPath("$.items[0].quantityOverride").value(true))
                .andReturn();
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM purchase_order_items
                WHERE purchase_order_id = ?
                  AND recommended_supplier_id_snapshot IS NULL
                  AND recommended_quantity_snapshot IS NULL
                """, Long.class, id)).isOne();
    }

    @Test
    void validatesRecommendationAndResultReferencesBeforePersistence() throws Exception {
        Material material = material();
        Material otherMaterial = material();
        Supplier supplier = supplier("SELECTED");
        supplies(supplier, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        RecommendationFixture fixture = recommendation(material, supplier, "200", staff);
        RecommendationFixture otherMaterialFixture = recommendation(otherMaterial, supplier, "200", staff);
        RecommendationFixture otherRecommendation = recommendation(material, supplier, "200", staff);
        long before = purchaseOrderRepository.count();

        create(staff, payload(supplier, item(material, "200", 999999999L, fixture.result().getId(), null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/v1/purchase-orders"));
        create(staff, payload(supplier, item(material, "200", otherMaterialFixture.recommendation().getId(),
                otherMaterialFixture.result().getId(), null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECOMMENDATION_REFERENCE"));
        create(staff, payload(supplier, item(material, "200", fixture.recommendation().getId(),
                otherRecommendation.result().getId(), null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUPPLIER_RECOMMENDATION_REFERENCE"));
        create(staff, payload(supplier, item(material, "200", fixture.recommendation().getId(),
                999999999L, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUPPLIER_RECOMMENDATION_REFERENCE"));
        assertThat(purchaseOrderRepository.count()).isEqualTo(before);
    }

    @Test
    void rejectsSelectedResultSupplierMismatchAndIncompleteInput() throws Exception {
        Material material = material();
        Supplier recommended = supplier("RECOMMENDED");
        Supplier selected = supplier("SELECTED");
        supplies(selected, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        RecommendationFixture fixture = recommendation(material, recommended, "200", staff, selected);
        long before = purchaseOrderRepository.count();

        create(staff, payload(selected,
                item(material, "200", fixture.recommendation().getId(), fixture.result().getId(),
                        "Alternate source", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUPPLIER_RECOMMENDATION_REFERENCE"));
        create(staff, payload(selected, incompleteItem(material, fixture.recommendation().getId(), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUPPLIER_RECOMMENDATION_REFERENCE"));
        create(staff, payload(selected, incompleteItem(material, null, fixture.alternateResult().getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECOMMENDATION_REFERENCE"));
        assertThat(purchaseOrderRepository.count()).isEqualTo(before);
    }

    @Test
    void keepsManualDraftEvidenceNullAndRollsBackAllItemsWhenLaterReferenceFails() throws Exception {
        Material first = material();
        Material second = material();
        Supplier supplier = supplier("SELECTED");
        supplies(supplier, first);
        supplies(supplier, second);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        RecommendationFixture fixture = recommendation(first, supplier, "10", staff);

        MvcResult manual = create(staff, payload(supplier, manualItem(first, "10")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].recommendationId").doesNotExist())
                .andExpect(jsonPath("$.items[0].supplierRecommendationResultId").doesNotExist())
                .andExpect(jsonPath("$.items[0].supplierOverrideReason").doesNotExist())
                .andExpect(jsonPath("$.items[0].quantityOverrideReason").doesNotExist())
                .andReturn();
        long manualId = ((Number) JsonPath.read(manual.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT recommendation_id, supplier_recommendation_result_id,
                       recommended_supplier_id_snapshot, recommended_quantity_snapshot,
                       supplier_override_reason, quantity_override_reason
                FROM purchase_order_items WHERE purchase_order_id = ?
                """, manualId).values()).containsOnlyNulls();

        long before = purchaseOrderRepository.count();
        create(staff, payload(supplier,
                item(first, "10", fixture.recommendation().getId(), fixture.result().getId(), null, null),
                item(second, "10", 999999999L, fixture.result().getId(), null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_NOT_FOUND"));
        assertThat(purchaseOrderRepository.count()).isEqualTo(before);
    }

    @Test
    void mysqlForeignKeysRejectInvalidRecommendationEvidence() throws Exception {
        Material material = material();
        Supplier supplier = supplier("MANUAL");
        supplies(supplier, material);
        User staff = user(UserRole.PROCUREMENT_STAFF);
        MvcResult manual = create(staff, payload(supplier, manualItem(material, "10")))
                .andExpect(status().isCreated()).andReturn();
        long purchaseOrderId = ((Number) JsonPath.read(manual.getResponse().getContentAsString(), "$.id")).longValue();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE purchase_order_items SET recommendation_id = 999999999 WHERE purchase_order_id = ?",
                purchaseOrderId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE purchase_order_items SET supplier_recommendation_result_id = 999999999 WHERE purchase_order_id = ?",
                purchaseOrderId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private RecommendationFixture recommendation(
            Material material, Supplier recommendedSupplier, String recommendedQuantity, User creator,
            Supplier... alternateSuppliers) {
        PurchaseRecommendation recommendation = PurchaseRecommendation.create(
                material,
                LocalDate.of(2026, 10, 8),
                LocalDate.of(2026, 10, 20),
                new BigDecimal("200"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("200"),
                RecommendationOutcome.SUPPLIER_RECOMMENDED,
                recommendedSupplier,
                new BigDecimal(recommendedQuantity),
                new BigDecimal("90"),
                creator,
                clock.instant());
        SupplierRecommendationResult recommendedResult = result(recommendation, recommendedSupplier, recommendedQuantity, 1);
        recommendation.addSupplierResult(recommendedResult);
        SupplierRecommendationResult alternateResult = null;
        int rank = 2;
        for (Supplier alternate : alternateSuppliers) {
            SupplierRecommendationResult candidate = result(recommendation, alternate, recommendedQuantity, rank++);
            recommendation.addSupplierResult(candidate);
            if (alternateResult == null) alternateResult = candidate;
        }
        recommendationRepository.saveAndFlush(recommendation);
        return new RecommendationFixture(recommendation, recommendedResult, alternateResult);
    }

    private SupplierRecommendationResult result(
            PurchaseRecommendation recommendation, Supplier supplier, String quantity, int rank) {
        BigDecimal recommendedQuantity = new BigDecimal(quantity);
        return SupplierRecommendationResult.qualified(
                recommendation, supplier, recommendedQuantity, BigDecimal.TEN,
                recommendedQuantity.multiply(BigDecimal.TEN), 1,
                new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("90"),
                new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("90"),
                new BigDecimal("90"), rank);
    }

    private RecommendationFixture noQualifiedSupplierRecommendation(
            Material material, Supplier candidate, User creator) {
        PurchaseRecommendation recommendation = PurchaseRecommendation.create(
                material,
                LocalDate.of(2026, 10, 8),
                LocalDate.of(2026, 10, 20),
                new BigDecimal("25"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("25"),
                RecommendationOutcome.NO_QUALIFIED_SUPPLIER,
                null,
                null,
                null,
                creator,
                clock.instant());
        SupplierRecommendationResult result = SupplierRecommendationResult.disqualified(
                recommendation, candidate, DisqualificationReason.DELIVERY_DATE_NOT_FEASIBLE);
        recommendation.addSupplierResult(result);
        recommendationRepository.saveAndFlush(recommendation);
        return new RecommendationFixture(recommendation, result, null);
    }

    private org.springframework.test.web.servlet.ResultActions create(User user, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/purchase-orders")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    private String payload(Supplier supplier, String... items) {
        return "{\"supplierId\":" + supplier.getId() + ",\"items\":[" + String.join(",", items) + "]}";
    }

    private String item(Material material, String quantity, Long recommendationId, Long resultId,
                        String supplierReason, String quantityReason) {
        return "{\"materialId\":" + material.getId()
                + ",\"quantity\":" + quantity
                + ",\"unitPrice\":10"
                + ",\"recommendationId\":" + recommendationId
                + ",\"supplierRecommendationResultId\":" + resultId
                + optionalJson("supplierOverrideReason", supplierReason)
                + optionalJson("quantityOverrideReason", quantityReason)
                + "}";
    }

    private String incompleteItem(Material material, Long recommendationId, Long resultId) {
        return "{\"materialId\":" + material.getId() + ",\"quantity\":200,\"unitPrice\":10"
                + (recommendationId == null ? "" : ",\"recommendationId\":" + recommendationId)
                + (resultId == null ? "" : ",\"supplierRecommendationResultId\":" + resultId)
                + "}";
    }

    private String manualItem(Material material, String quantity) {
        return "{\"materialId\":" + material.getId() + ",\"quantity\":" + quantity + ",\"unitPrice\":10}";
    }

    private String optionalJson(String name, String value) {
        return value == null ? "" : ",\"" + name + "\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Material material() {
        return materialRepository.saveAndFlush(Material.create(
                "MAT-SP601-" + suffix(), "Material", BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
    }

    private Supplier supplier(String name) {
        return supplierRepository.saveAndFlush(Supplier.create(
                "SUP-SP601-" + suffix(), name, null, new BigDecimal("90"), new BigDecimal("90"), clock.instant()));
    }

    private SupplierMaterial supplies(Supplier supplier, Material material) {
        return supplierMaterialRepository.saveAndFlush(SupplierMaterial.create(
                supplier, material, BigDecimal.TEN, BigDecimal.ONE, null, 1, Currency.TWD, clock.instant()));
    }

    private User user(UserRole role) {
        return userRepository.saveAndFlush(User.create(
                "sp601-" + suffix(), "hash", role.name(), role, clock.instant()));
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

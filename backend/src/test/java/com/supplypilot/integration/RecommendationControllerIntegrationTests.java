package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.supplypilot.demand.domain.Demand;
import com.supplypilot.demand.repository.DemandRepository;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class RecommendationControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private DemandRepository demandRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private SupplierMaterialRepository supplierMaterialRepository;
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
    void createsPeriodEndAggregatedRecommendationWithBoundaryDemandIncomingAndRanking() throws Exception {
        Material material = material("50", "20");
        User planner = user(UserRole.PRODUCTION_PLANNER);
        User buyer = user(UserRole.PROCUREMENT_STAFF);
        demand(material, planner, "100", LocalDate.of(2026, 10, 8));
        demand(material, planner, "200", LocalDate.of(2026, 10, 20));
        demand(material, planner, "999", LocalDate.of(2026, 10, 21));
        Supplier fast = supplier("FAST", "90", "90");
        Supplier late = supplier("LATE", "95", "95");
        supplierMaterial(fast, material, "10", "1", 12); // 2026-10-08 + 12 <= 2026-10-20
        supplierMaterial(late, material, "1", "1", 13);  // excluded by periodEnd feasibility
        insertOrderedIncoming(material, buyer, LocalDate.of(2026, 10, 20), "70");
        insertOrderedIncoming(material, buyer, LocalDate.of(2026, 10, 21), "999");

        MvcResult result = create(buyer, material, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 20))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("SUPPLIER_RECOMMENDED"))
                .andExpect(jsonPath("$.calculation.expectedDemand").value(300))
                .andExpect(jsonPath("$.calculation.incomingInventory").value(70))
                .andExpect(jsonPath("$.calculation.netRequirement").value(260))
                .andExpect(jsonPath("$.recommendation.supplierId").value(fast.getId()))
                .andReturn();
        Long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by FROM purchase_recommendation WHERE id = ?", Long.class, id)).isEqualTo(buyer.getId());
        Long fastResultId = resultId(id, fast);
        Long lateResultId = resultId(id, late);
        MvcResult supplierResults = mockMvc.perform(
                        get("/api/v1/recommendations/{id}/suppliers", id).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.supplierId == " + fast.getId() + ")].qualified").value(true))
                .andExpect(jsonPath("$[?(@.supplierId == " + late.getId() + ")].disqualifiedReason")
                        .value("DELIVERY_DATE_NOT_FEASIBLE"))
                .andReturn();
        String supplierResultsJson = supplierResults.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.<java.util.List<?>>read(
                supplierResultsJson, "$[?(@.supplierId == " + fast.getId() + ")].id").getFirst()).longValue())
                .isEqualTo(fastResultId);
        assertThat(((Number) JsonPath.<java.util.List<?>>read(
                supplierResultsJson, "$[?(@.supplierId == " + late.getId() + ")].id").getFirst()).longValue())
                .isEqualTo(lateResultId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM supplier_recommendation_result WHERE recommendation_id = ? AND id IN (?, ?)",
                Long.class, id, fastResultId, lateResultId)).isEqualTo(2L);
    }

    @Test
    void persistsBothNonPurchaseOutcomesAndRejectsUnauthorizedRequests() throws Exception {
        User buyer = user(UserRole.PROCUREMENT_STAFF);
        Material covered = material("0", "200");
        demand(covered, buyer, "100", LocalDate.of(2026, 10, 20));
        create(buyer, covered, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("PURCHASE_NOT_REQUIRED"))
                .andExpect(jsonPath("$.recommendation.purchaseRequired").value(false));

        Material unqualified = material("0", "0");
        demand(unqualified, buyer, "10", LocalDate.of(2026, 10, 20));
        Supplier supplier = supplier("TOO-LATE", "90", "90");
        supplierMaterial(supplier, unqualified, "10", "1", 13);
        create(buyer, unqualified, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("NO_QUALIFIED_SUPPLIER"))
                .andExpect(jsonPath("$.recommendation.supplierId").doesNotExist());
        Material noCandidates = material("0", "0");
        demand(noCandidates, buyer, "10", LocalDate.of(2026, 10, 20));
        create(buyer, noCandidates, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("NO_QUALIFIED_SUPPLIER"));
        create(buyer, noCandidates, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 20))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        mockMvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON)
                        .content(payload(unqualified, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))))
                .andExpect(status().isUnauthorized());
        create(user(UserRole.WAREHOUSE_STAFF), unqualified, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))
                .andExpect(status().isForbidden());
    }

    @Test
    void retainsCalculationSnapshotAfterMasterDataChangesAndRecalculationCreatesNewId() throws Exception {
        Material material = material("5", "0");
        User buyer = user(UserRole.PROCUREMENT_STAFF);
        demand(material, buyer, "20", LocalDate.of(2026, 10, 20));
        Supplier supplier = supplier("IMMUTABLE", "90", "91");
        SupplierMaterial relation = supplierMaterial(supplier, material, "10", "1", 1);
        MvcResult first = create(buyer, material, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))
                .andExpect(status().isCreated()).andReturn();
        Long firstId = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.id")).longValue();
        String originalMaterialCode = material.getCode();
        String originalSupplierCode = supplier.getCode();
        relation.update(new BigDecimal("99"), null, null, 2, null, clock.instant());
        supplierMaterialRepository.saveAndFlush(relation);
        supplier.update(null, null, new BigDecimal("1"), new BigDecimal("2"), null, clock.instant());
        supplierRepository.saveAndFlush(supplier);
        jdbcTemplate.update("UPDATE materials SET code = ?, name = ? WHERE id = ?", "MAT-RENAMED", "Renamed material", material.getId());
        jdbcTemplate.update("UPDATE suppliers SET code = ?, name = ? WHERE id = ?", "SUP-RENAMED", "Renamed supplier", supplier.getId());

        mockMvc.perform(get("/api/v1/recommendations/{id}", firstId).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.material.code").value(originalMaterialCode))
                .andExpect(jsonPath("$.material.name").value("Material"))
                .andExpect(jsonPath("$.recommendation.supplierName").value("IMMUTABLE"))
                .andExpect(jsonPath("$.recommendation.score").value(97.1));

        mockMvc.perform(get("/api/v1/recommendations/{id}/suppliers", firstId).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].supplierCode").value(originalSupplierCode))
                .andExpect(jsonPath("$[0].supplierName").value("IMMUTABLE"))
                .andExpect(jsonPath("$[0].unitPrice").value(10))
                .andExpect(jsonPath("$[0].leadTimeDays").value(1))
                .andExpect(jsonPath("$[0].reliabilityScoreSnapshot").value(90))
                .andExpect(jsonPath("$[0].qualityScoreSnapshot").value(91))
                .andExpect(jsonPath("$[0].totalScore").value(97.1));
        MvcResult second = create(buyer, material, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))
                .andExpect(status().isCreated()).andReturn();
        Long secondId = ((Number) JsonPath.read(second.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(secondId).isNotEqualTo(firstId);
    }

    @Test
    void rollsBackHeaderAndAllResultsWhenDatabaseRejectsOneCandidateResult() throws Exception {
        Material material = material("0", "0");
        User buyer = user(UserRole.PROCUREMENT_STAFF);
        demand(material, buyer, "100", LocalDate.of(2026, 10, 20));
        Supplier normal = supplier("OK", "90", "90");
        Supplier constrained = supplier("CONSTRAINED", "90", "90");
        supplierMaterial(normal, material, "10", "1", 1);
        SupplierMaterial failing = supplierMaterial(constrained, material, "20", "1", 1);

        jdbcTemplate.execute("ALTER TABLE supplier_recommendation_result ADD CONSTRAINT chk_sp504_forced_result_failure CHECK (supplier_id <> " + failing.getSupplier().getId() + ")");
        try {
            create(buyer, material, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE supplier_recommendation_result DROP CHECK chk_sp504_forced_result_failure");
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'supplier_recommendation_result'
                  AND constraint_name = 'chk_sp504_forced_result_failure'
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_recommendation WHERE material_id = ?", Long.class, material.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM supplier_recommendation_result result
                JOIN purchase_recommendation recommendation ON recommendation.id = result.recommendation_id
                WHERE recommendation.material_id = ?
                """, Long.class, material.getId())).isZero();
    }

    @Test
    void persistsFullPrecisionScoresAndReturnsThreeSupplierRankingFromSnapshots() throws Exception {
        Material material = material("0", "0");
        User buyer = user(UserRole.PROCUREMENT_STAFF);
        demand(material, buyer, "1", LocalDate.of(2026, 10, 20));
        Supplier lowest = supplier("LOWEST", "0", "0");
        Supplier fractional = supplier("FRACTIONAL", "0", "0");
        Supplier highest = supplier("HIGHEST", "0", "0");
        supplierMaterial(lowest, material, "3", "1", 0);
        supplierMaterial(fractional, material, "7", "1", 0);
        supplierMaterial(highest, material, "11", "1", 0);

        MvcResult created = create(buyer, material, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))
                .andExpect(status().isCreated()).andReturn();
        Long recommendationId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        BigDecimal storedScore = jdbcTemplate.queryForObject("""
                SELECT total_score FROM supplier_recommendation_result
                WHERE recommendation_id = ? AND supplier_id = ?
                """, BigDecimal.class, recommendationId, fractional.getId());
        assertThat(storedScore).isEqualByComparingTo("47.14285714284000");
        assertThat(storedScore.scale()).isEqualTo(14);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT price_score FROM supplier_recommendation_result
                WHERE recommendation_id = ? AND supplier_id = ?
                """, BigDecimal.class, recommendationId, fractional.getId()))
                .isEqualByComparingTo("42.857142857100");
        BigDecimal recommendedSupplierScore = jdbcTemplate.queryForObject("""
                SELECT recommended_supplier_score FROM purchase_recommendation WHERE id = ?
                """, BigDecimal.class, recommendationId);
        assertThat(recommendedSupplierScore).isEqualByComparingTo("70.00000000000000");
        assertThat(recommendedSupplierScore.scale()).isEqualTo(14);

        mockMvc.perform(get("/api/v1/recommendations/{id}/suppliers", recommendationId)
                        .header("Authorization", bearer(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].supplierCode").value(lowest.getCode()))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[1].supplierCode").value(fractional.getCode()))
                .andExpect(jsonPath("$[1].rank").value(2))
                .andExpect(jsonPath("$[1].priceScore").value(42.8571428571))
                .andExpect(jsonPath("$[1].totalScore").value(47.14285714284))
                .andExpect(jsonPath("$[2].supplierCode").value(highest.getCode()))
                .andExpect(jsonPath("$[2].rank").value(3));
    }

    private org.springframework.test.web.servlet.ResultActions create(User user, Material material, LocalDate start, LocalDate end) throws Exception {
        return mockMvc.perform(post("/api/v1/recommendations").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(payload(material, start, end)));
    }

    private String payload(Material material, LocalDate start, LocalDate end) {
        return "{\"materialId\":" + material.getId() + ",\"periodStart\":\"" + start + "\",\"periodEnd\":\"" + end + "\"}";
    }

    private Material material(String safetyStock, String inventoryQuantity) {
        Material material = materialRepository.saveAndFlush(Material.create("MAT-REC-" + suffix(), "Material", BaseUnit.KG, new BigDecimal(safetyStock), clock.instant()));
        Inventory inventory = Inventory.create(material, clock.instant());
        if (!new BigDecimal(inventoryQuantity).equals(BigDecimal.ZERO)) inventory.adjust(new BigDecimal(inventoryQuantity), clock.instant());
        inventoryRepository.saveAndFlush(inventory);
        return material;
    }

    private void demand(Material material, User user, String quantity, LocalDate requiredDate) {
        demandRepository.saveAndFlush(Demand.create(material, new BigDecimal(quantity), requiredDate, null, user, clock.instant()));
    }

    private Supplier supplier(String code, String reliability, String quality) {
        return supplierRepository.saveAndFlush(Supplier.create("S-" + code + '-' + suffix().substring(0, 12), code, null,
                new BigDecimal(reliability), new BigDecimal(quality), clock.instant()));
    }

    private SupplierMaterial supplierMaterial(Supplier supplier, Material material, String price, String moq, int leadDays) {
        return supplierMaterialRepository.saveAndFlush(SupplierMaterial.create(supplier, material, new BigDecimal(price),
                new BigDecimal(moq), null, leadDays, Currency.TWD, clock.instant()));
    }

    private void insertOrderedIncoming(Material material, User user, LocalDate deliveryDate, String quantity) {
        Supplier supplier = supplier("INCOMING", "80", "80");
        String number = "PO-REC-" + suffix();
        java.sql.Timestamp now = java.sql.Timestamp.from(clock.instant());
        jdbcTemplate.update("INSERT INTO purchase_orders (po_number, supplier_id, status, expected_delivery_date, created_by, created_at, updated_at, version) VALUES (?, ?, 'ORDERED', ?, ?, ?, ?, 0)",
                number, supplier.getId(), deliveryDate, user.getId(), now, now);
        Long poId = jdbcTemplate.queryForObject("SELECT id FROM purchase_orders WHERE po_number = ?", Long.class, number);
        jdbcTemplate.update("INSERT INTO purchase_order_items (purchase_order_id, material_id, quantity, unit_price, unit, created_at) VALUES (?, ?, ?, 0, 'KG', ?)",
                poId, material.getId(), new BigDecimal(quantity), now);
    }

    private Long resultId(Long recommendationId, Supplier supplier) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM supplier_recommendation_result
                WHERE recommendation_id = ? AND supplier_id = ?
                """, Long.class, recommendationId, supplier.getId());
    }

    private User user(UserRole role) {
        return userRepository.saveAndFlush(User.create("rec-" + suffix(), "hash", role.name(), role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private String suffix() { return UUID.randomUUID().toString().replace("-", ""); }
}

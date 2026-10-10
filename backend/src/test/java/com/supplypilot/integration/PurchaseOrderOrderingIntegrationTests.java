package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.recommendation.query.IncomingInventoryQuery;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.testsupport.TestDataConstants;
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

class PurchaseOrderOrderingIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private SupplierMaterialRepository supplierMaterialRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IncomingInventoryQuery incomingInventoryQuery;

    private MockMvc mockMvc;
    private JwtTokenService tokens;
    private Supplier supplier;
    private Material material;
    private Material secondMaterial;
    private User staff;
    private User manager;
    private User admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tokens = new JwtTokenService(jwtProperties, clock);
        supplier = supplierRepository.saveAndFlush(Supplier.create(
                "SUP-611-" + suffix(), "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        material = materialRepository.saveAndFlush(Material.create(
                "MAT-611-" + suffix(), "Material", BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        secondMaterial = materialRepository.saveAndFlush(Material.create(
                "MAT-611-" + suffix(), "Second", BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        link(material);
        link(secondMaterial);
        staff = user(UserRole.PROCUREMENT_STAFF);
        manager = user(UserRole.PROCUREMENT_MANAGER);
        admin = user(UserRole.ADMIN);
    }

    @Test
    void approvedOrderIsMarkedOrderedWithBackendAuditAndStartsCountingAsIncoming() throws Exception {
        long id = approved(TestDataConstants.TEST_TODAY.plusDays(2), material, secondMaterial);
        assertThat(incomingInventoryQuery.calculateIncomingQuantity(
                material.getId(), TestDataConstants.TEST_TODAY.plusDays(2))).isZero();

        order(staff, id, "{\"orderedBy\":999,\"orderedAt\":\"1999-01-01T00:00:00Z\",\"status\":\"RECEIVED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ORDERED"))
                .andExpect(jsonPath("$.orderedBy").value(staff.getId()))
                .andExpect(jsonPath("$.orderedByDisplayName").value(staff.getDisplayName()))
                .andExpect(jsonPath("$.orderedAt").value(clock.instant().toString()))
                .andExpect(jsonPath("$.isOverdue").value(false));

        mockMvc.perform(get("/api/v1/purchase-orders/{id}", id).header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderedBy").value(staff.getId()))
                .andExpect(jsonPath("$.orderedAt").value(clock.instant().toString()));
        assertThat(incomingInventoryQuery.calculateIncomingQuantity(
                material.getId(), TestDataConstants.TEST_TODAY.plusDays(2))).isEqualByComparingTo("2");

        var row = jdbcTemplate.queryForMap(
                "SELECT status, ordered_by, ordered_at, updated_at FROM purchase_orders WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("ORDERED");
        assertThat(((Number) row.get("ordered_by")).longValue()).isEqualTo(staff.getId());
        LocalDateTime expected = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        assertThat(row.get("ordered_at")).isEqualTo(expected);
        assertThat(row.get("updated_at")).isEqualTo(expected);
    }

    @Test
    void rejectsIllegalLifecycleMissingRequiredDataAndRepeatedOrdering() throws Exception {
        order(staff, 999999999L, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_FOUND"));
        long draft = draft(TestDataConstants.TEST_TODAY.plusDays(1), material);
        order(staff, draft, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));

        long rejected = draft(TestDataConstants.TEST_TODAY.plusDays(1), material);
        reject(rejected);
        order(staff, rejected, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));

        long ordered = approved(TestDataConstants.TEST_TODAY.plusDays(1), material);
        order(staff, ordered, null).andExpect(status().isOk());
        order(staff, ordered, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));

        long received = approved(TestDataConstants.TEST_TODAY.minusDays(1), material);
        order(staff, received, null).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE purchase_orders SET status = 'RECEIVED' WHERE id = ?", received);
        order(staff, received, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));

        long missingDate = approved(null, material);
        order(staff, missingDate, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPECTED_DELIVERY_DATE_REQUIRED"));

        long missingItems = approved(TestDataConstants.TEST_TODAY.plusDays(1), material);
        jdbcTemplate.update("DELETE FROM purchase_order_items WHERE purchase_order_id = ?", missingItems);
        order(staff, missingItems, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void enforcesOrderAndQueryRoleMatricesWithoutAllowingAdminToBypassLifecycle() throws Exception {
        long approvedForStaff = approved(TestDataConstants.TEST_TODAY.plusDays(1), material);
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/order", approvedForStaff))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        for (UserRole role : new UserRole[] {UserRole.PROCUREMENT_MANAGER, UserRole.PRODUCTION_PLANNER,
                UserRole.WAREHOUSE_STAFF}) {
            order(user(role), approvedForStaff, null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        order(staff, approvedForStaff, null).andExpect(status().isOk());

        long approvedForAdmin = approved(TestDataConstants.TEST_TODAY.plusDays(1), material);
        order(admin, approvedForAdmin, null).andExpect(status().isOk());
        long draftForAdmin = draft(TestDataConstants.TEST_TODAY.plusDays(1), material);
        order(admin, draftForAdmin, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));

        mockMvc.perform(get("/api/v1/purchase-orders").param("supplierId", supplier.getId().toString()))
                .andExpect(status().isUnauthorized());
        for (User actor : new User[] {staff, manager, admin}) {
            list(actor, "supplierId", supplier.getId().toString()).andExpect(status().isOk());
        }
        list(user(UserRole.PRODUCTION_PLANNER), "supplierId", supplier.getId().toString())
                .andExpect(status().isForbidden());
    }

    @Test
    void filtersOverdueAndCombinedCriteriaWithStablePaginationAndNoDuplicateMaterialRows() throws Exception {
        long overdue = ordered(TestDataConstants.TEST_TODAY.minusDays(1), material, secondMaterial);
        long dueToday = ordered(TestDataConstants.TEST_TODAY, material);
        long dueTomorrow = ordered(TestDataConstants.TEST_TODAY.plusDays(1), material);
        long approvedPastDue = approved(TestDataConstants.TEST_TODAY.minusDays(2), material);
        long received = ordered(TestDataConstants.TEST_TODAY.minusDays(3), material);
        jdbcTemplate.update("UPDATE purchase_orders SET status = 'RECEIVED' WHERE id = ?", received);

        list(manager, "supplierId", supplier.getId().toString(), "overdue", "true")
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(overdue))
                .andExpect(jsonPath("$.content[0].status").value("ORDERED"))
                .andExpect(jsonPath("$.content[0].isOverdue").value(true));
        list(manager, "supplierId", supplier.getId().toString(), "overdue", "false")
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4));
        list(manager, "supplierId", supplier.getId().toString())
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
        for (long notOverdue : new long[] {dueToday, dueTomorrow, approvedPastDue, received}) {
            mockMvc.perform(get("/api/v1/purchase-orders/{id}", notOverdue)
                            .header("Authorization", bearer(manager)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.isOverdue").value(false));
        }
        mockMvc.perform(get("/api/v1/purchase-orders/{id}", approvedPastDue)
                        .header("Authorization", bearer(manager)))
                .andExpect(jsonPath("$.orderedBy").doesNotExist())
                .andExpect(jsonPath("$.orderedAt").doesNotExist());

        list(manager, "supplierId", supplier.getId().toString(), "materialId", material.getId().toString(),
                "status", "ORDERED", "expectedDeliveryFrom", TestDataConstants.TEST_TODAY.toString(),
                "expectedDeliveryTo", TestDataConstants.TEST_TODAY.plusDays(1).toString(),
                "page", "0", "size", "1", "sort", "expectedDeliveryDate,asc")
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
        list(manager, "supplierId", supplier.getId().toString(), "materialId", secondMaterial.getId().toString())
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        list(manager, "sort", "supplier.name,desc").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        list(manager, "createdFrom", "2026-10-09", "createdTo", "2026-10-08")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void databaseFailureRollsBackOrderingStatusAndAudit() throws Exception {
        long id = approved(TestDataConstants.TEST_TODAY.plusDays(1), material);
        jdbcTemplate.execute("ALTER TABLE purchase_orders ADD CONSTRAINT chk_sp611_forced_order_failure "
                + "CHECK (ordered_by IS NULL OR ordered_by <> " + staff.getId() + ")");
        try {
            order(staff, id, null).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE purchase_orders DROP CHECK chk_sp611_forced_order_failure");
        }
        var row = jdbcTemplate.queryForMap(
                "SELECT status, ordered_by, ordered_at FROM purchase_orders WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("ordered_by")).isNull();
        assertThat(row.get("ordered_at")).isNull();
    }

    private long ordered(LocalDate date, Material... materials) throws Exception {
        long id = approved(date, materials);
        order(staff, id, null).andExpect(status().isOk());
        return id;
    }

    private long approved(LocalDate date, Material... materials) throws Exception {
        long id = draft(date, materials);
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/approve", id)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk());
        return id;
    }

    private long draft(LocalDate date, Material... materials) throws Exception {
        StringBuilder items = new StringBuilder();
        for (Material value : materials) {
            if (!items.isEmpty()) items.append(',');
            items.append("{\"materialId\":").append(value.getId())
                    .append(",\"quantity\":2,\"unitPrice\":10}");
        }
        String dateJson = date == null ? "null" : "\"" + date + "\"";
        MvcResult result = mockMvc.perform(post("/api/v1/purchase-orders")
                        .header("Authorization", bearer(staff)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":" + supplier.getId() + ",\"expectedDeliveryDate\":"
                                + dateJson + ",\"items\":[" + items + "]}"))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void reject(long id) throws Exception {
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/reject", id)
                        .header("Authorization", bearer(manager)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not needed\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions order(User actor, long id, String body) throws Exception {
        var request = post("/api/v1/purchase-orders/{id}/order", id).header("Authorization", bearer(actor));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions list(User actor, String... parameters) throws Exception {
        var request = get("/api/v1/purchase-orders").header("Authorization", bearer(actor));
        for (int index = 0; index < parameters.length; index += 2) {
            request.param(parameters[index], parameters[index + 1]);
        }
        return mockMvc.perform(request);
    }

    private void link(Material linkedMaterial) {
        supplierMaterialRepository.saveAndFlush(SupplierMaterial.create(
                supplier, linkedMaterial, BigDecimal.TEN, BigDecimal.ONE, null, 0, Currency.TWD, clock.instant()));
    }

    private User user(UserRole role) {
        return userRepository.saveAndFlush(User.create(
                "sp611-" + role.name().toLowerCase() + "-" + suffix(), "hash", role.name(), role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

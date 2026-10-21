package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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

class PurchaseOrderLifecycleIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private SupplierMaterialRepository supplierMaterialRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private JwtTokenService tokens;
    private Supplier supplier;
    private Material material;
    private User staff;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tokens = new JwtTokenService(jwtProperties, clock);
        supplier = supplierRepository.saveAndFlush(Supplier.create(
                "SUP-SP610-" + suffix(), "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        material = materialRepository.saveAndFlush(Material.create(
                "MAT-SP610-" + suffix(), "Material", BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        supplierMaterialRepository.saveAndFlush(SupplierMaterial.create(
                supplier, material, BigDecimal.ZERO, BigDecimal.ONE, null, 0, Currency.TWD, clock.instant()));
        staff = user(UserRole.PROCUREMENT_STAFF);
    }

    @Test
    void managerAndAdminCanApproveAndRejectWithJwtAuditPersistedAndReadable() throws Exception {
        User manager = user(UserRole.PROCUREMENT_MANAGER);
        User admin = user(UserRole.ADMIN);

        long managerApproved = draftIdWithDate();
        approve(manager, managerApproved, "{\"approvedBy\":999,\"status\":\"REJECTED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value(manager.getId()))
                .andExpect(jsonPath("$.approvedByDisplayName").value(manager.getDisplayName()))
                .andExpect(jsonPath("$.approvedAt").value(clock.instant().toString()))
                .andExpect(jsonPath("$.createdBy").value(staff.getId()))
                .andExpect(jsonPath("$.items.length()").value(1));
        assertAudit(managerApproved, "APPROVED", manager.getId(), null, null);
        mockMvc.perform(get("/api/v1/purchase-orders/{id}", managerApproved)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedBy").value(manager.getId()))
                .andExpect(jsonPath("$.approvedAt").value(clock.instant().toString()));

        long adminApproved = draftIdWithDate();
        approve(admin, adminApproved, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedBy").value(admin.getId()));
        reject(admin, adminApproved, "{\"reason\":\"cannot bypass\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));

        long managerRejected = draftId();
        reject(manager, managerRejected, "{\"reason\":\"Quantity exceeds plan.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("Quantity exceeds plan."))
                .andExpect(jsonPath("$.rejectedBy").value(manager.getId()))
                .andExpect(jsonPath("$.rejectedByDisplayName").value(manager.getDisplayName()))
                .andExpect(jsonPath("$.rejectedAt").value(clock.instant().toString()));
        assertAudit(managerRejected, "REJECTED", null, manager.getId(), "Quantity exceeds plan.");

        mockMvc.perform(get("/api/v1/purchase-orders/{id}", managerRejected)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectedBy").value(manager.getId()))
                .andExpect(jsonPath("$.rejectedAt").value(clock.instant().toString()));

        long adminRejected = draftId();
        reject(admin, adminRejected, "{\"reason\":\"Admin rejected\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.rejectedBy").value(admin.getId()));
        approve(admin, adminRejected, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));
        mockMvc.perform(patch("/api/v1/purchase-orders/{id}", adminRejected)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedDeliveryDate\":\"2026-11-01\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PO_NOT_EDITABLE"));
    }

    @Test
    void missingDateApprovalLeavesPersistedDraftUntouchedThenPatchAllowsApprovalAndOrdering() throws Exception {
        User manager = user(UserRole.PROCUREMENT_MANAGER);
        long id = draftId();
        var before = jdbcTemplate.queryForMap(
                "SELECT status, approved_by, approved_at, updated_at, version FROM purchase_orders WHERE id = ?", id);

        approve(manager, id, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPECTED_DELIVERY_DATE_REQUIRED"));
        var after = jdbcTemplate.queryForMap(
                "SELECT status, approved_by, approved_at, updated_at, version FROM purchase_orders WHERE id = ?", id);
        assertThat(after).isEqualTo(before);
        assertThat(after.get("status")).isEqualTo("DRAFT");
        assertThat(after.get("approved_by")).isNull();
        assertThat(after.get("approved_at")).isNull();

        mockMvc.perform(patch("/api/v1/purchase-orders/{id}", id)
                        .header("Authorization", bearer(staff)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedDeliveryDate\":\"2026-10-30\"}"))
                .andExpect(status().isOk());
        approve(manager, id, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedBy").value(manager.getId()));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/order", id)
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ORDERED"));
    }

    @Test
    void rejectedPurchaseOrderCannotBeClaimedOrAssigned() throws Exception {
        User manager = user(UserRole.PROCUREMENT_MANAGER);
        User admin = user(UserRole.ADMIN);
        long id = draftId();
        reject(manager, id, "{\"reason\":\"Closed\"}").andExpect(status().isOk());
        jdbcTemplate.update("UPDATE purchase_orders SET assigned_to = NULL WHERE id = ?", id);

        mockMvc.perform(post("/api/v1/purchase-orders/{id}/claim", id)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/assign", id)
                        .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + admin.getId() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));
        assertThat(statusOf(id)).isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject("SELECT assigned_to FROM purchase_orders WHERE id = ?", Long.class, id)).isNull();

        // RECEIVED can remain billable/in-progress in V2, so ownership assignment stays allowed.
        jdbcTemplate.update("UPDATE purchase_orders SET status = 'RECEIVED' WHERE id = ?", id);
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/claim", id)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.assignedTo").value(admin.getId()));
    }

    @Test
    void enforcesAuthenticationAndCompleteRoleMatrixForBothActions() throws Exception {
        long id = draftId();
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/approve", id))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/reject", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"reason\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        for (UserRole role : new UserRole[] {
                UserRole.PROCUREMENT_STAFF, UserRole.PRODUCTION_PLANNER, UserRole.WAREHOUSE_STAFF}) {
            User forbidden = user(role);
            approve(forbidden, id, null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            reject(forbidden, id, "{\"reason\":\"reason\"}").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        assertThat(statusOf(id)).isEqualTo("DRAFT");
    }

    @Test
    void rejectsMissingBlankAndClientOwnedRejectFieldsWithSpecifiedCodes() throws Exception {
        User manager = user(UserRole.PROCUREMENT_MANAGER);
        for (String body : new String[] {null, "{}", "{\"reason\":null}", "{\"reason\":\"\"}",
                "{\"reason\":\"   \"}"}) {
            long id = draftId();
            reject(manager, id, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REJECT_REASON_REQUIRED"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").isNotEmpty())
                    .andExpect(jsonPath("$.path").value("/api/v1/purchase-orders/" + id + "/reject"));
            assertThat(statusOf(id)).isEqualTo("DRAFT");
        }

        long protectedFields = draftId();
        reject(manager, protectedFields,
                "{\"reason\":\"reason\",\"status\":\"REJECTED\",\"rejectedBy\":999,\"rejectedAt\":\"2020-01-01T00:00:00Z\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(statusOf(protectedFields)).isEqualTo("DRAFT");
    }

    @Test
    void rejectsNotFoundEmptyAndEveryIllegalStateTransition() throws Exception {
        User manager = user(UserRole.PROCUREMENT_MANAGER);
        approve(manager, 999999999L, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_FOUND"));
        reject(manager, 999999999L, "{\"reason\":\"reason\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_FOUND"));

        long empty = draftId();
        jdbcTemplate.update("DELETE FROM purchase_order_items WHERE purchase_order_id = ?", empty);
        approve(manager, empty, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        for (String existing : new String[] {"APPROVED", "REJECTED", "ORDERED", "RECEIVED"}) {
            long approveId = draftId();
            jdbcTemplate.update("UPDATE purchase_orders SET status = ? WHERE id = ?", existing, approveId);
            approve(manager, approveId, null).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));

            long rejectId = draftId();
            jdbcTemplate.update("UPDATE purchase_orders SET status = ? WHERE id = ?", existing, rejectId);
            reject(manager, rejectId, "{\"reason\":\"reason\"}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_PO_STATUS_TRANSITION"));
        }
    }

    @Test
    void lifecycleDatabaseFailureRollsBackStatusAndAllAuditFields() throws Exception {
        User manager = user(UserRole.PROCUREMENT_MANAGER);
        long approvalId = draftIdWithDate();
        jdbcTemplate.execute("ALTER TABLE purchase_orders ADD CONSTRAINT chk_sp610_forced_approval_failure "
                + "CHECK (approved_by_username_snapshot IS NULL OR approved_by_username_snapshot <> '"
                + manager.getUsername() + "')");
        try {
            approve(manager, approvalId, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE purchase_orders DROP CHECK chk_sp610_forced_approval_failure");
        }
        var approvalRow = jdbcTemplate.queryForMap(
                "SELECT status, approved_by, approved_at FROM purchase_orders WHERE id = ?", approvalId);
        assertThat(approvalRow.get("status")).isEqualTo("DRAFT");
        assertThat(approvalRow.get("approved_by")).isNull();
        assertThat(approvalRow.get("approved_at")).isNull();

        long rejectionId = draftId();
        jdbcTemplate.execute("ALTER TABLE purchase_orders ADD CONSTRAINT chk_sp610_forced_failure "
                + "CHECK (reject_reason IS NULL OR reject_reason <> 'must roll back')");
        try {
            reject(manager, rejectionId, "{\"reason\":\"must roll back\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE purchase_orders DROP CHECK chk_sp610_forced_failure");
        }

        var row = jdbcTemplate.queryForMap(
                "SELECT status, reject_reason, rejected_by, rejected_at FROM purchase_orders WHERE id = ?", rejectionId);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("reject_reason")).isNull();
        assertThat(row.get("rejected_by")).isNull();
        assertThat(row.get("rejected_at")).isNull();
    }

    private org.springframework.test.web.servlet.ResultActions approve(User user, long id, String body) throws Exception {
        var request = post("/api/v1/purchase-orders/{id}/approve", id).header("Authorization", bearer(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions reject(User user, long id, String body) throws Exception {
        var request = post("/api/v1/purchase-orders/{id}/reject", id).header("Authorization", bearer(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request);
    }

    private long draftId() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/purchase-orders")
                        .header("Authorization", bearer(staff)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":" + supplier.getId() + ",\"items\":[{\"materialId\":"
                                + material.getId() + ",\"quantity\":2,\"unitPrice\":10}]}"))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long draftIdWithDate() throws Exception {
        long id = draftId();
        mockMvc.perform(patch("/api/v1/purchase-orders/{id}", id)
                        .header("Authorization", bearer(staff)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedDeliveryDate\":\"2026-10-30\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private void assertAudit(long id, String status, Long approvedBy, Long rejectedBy, String reason) {
        var row = jdbcTemplate.queryForMap(
                "SELECT status, approved_by, approved_at, reject_reason, rejected_by, rejected_at "
                        + "FROM purchase_orders WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(asLong(row.get("approved_by"))).isEqualTo(approvedBy);
        assertThat(asLong(row.get("rejected_by"))).isEqualTo(rejectedBy);
        assertThat(row.get("reject_reason")).isEqualTo(reason);
        LocalDateTime auditTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        assertThat(row.get("approved_at")).isEqualTo(approvedBy == null ? null : auditTime);
        assertThat(row.get("rejected_at")).isEqualTo(rejectedBy == null ? null : auditTime);
    }

    private Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private String statusOf(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, id);
    }

    private User user(UserRole role) {
        return userRepository.saveAndFlush(User.create(
                "sp610-" + role.name().toLowerCase() + "-" + suffix(), "hash", role.name(), role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

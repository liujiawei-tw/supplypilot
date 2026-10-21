package com.supplypilot.integration;

import com.supplypilot.material.domain.*;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.*;
import com.supplypilot.supplier.domain.*;
import com.supplypilot.supplier.repository.*;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SupplierMaterialControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired WebApplicationContext context; @Autowired JwtProperties jwtProperties; @Autowired Clock clock;
    @Autowired SupplierRepository supplierRepository; @Autowired MaterialRepository materialRepository; @Autowired SupplierMaterialRepository supplierMaterialRepository;
    @Autowired UserRepository userRepository;
    MockMvc mockMvc; JwtTokenService tokens;
    @BeforeEach void setUp() { mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build(); tokens = new JwtTokenService(jwtProperties, clock); }
    @Test void adminCreatesListsUpdatesAndRetainsInactiveRecord() throws Exception {
        Supplier supplier = supplierRepository.saveAndFlush(Supplier.create("SUP-SM-" + System.nanoTime(), "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        Material material = materialRepository.saveAndFlush(Material.create("MAT-SM-" + System.nanoTime(), "Material", BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        String body = "{\"supplierId\":" + supplier.getId() + ",\"materialId\":" + material.getId() + ",\"unitPrice\":0,\"moq\":1,\"leadTimeDays\":0,\"currency\":\"TWD\"}";
        MvcResult result = mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER_MATERIAL"));
        mockMvc.perform(patch("/api/v1/supplier-materials/{id}", id).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"unitPrice\":80,\"status\":\"INACTIVE\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.unitPrice").value(80)).andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(get("/api/v1/supplier-materials/{id}", id).header("Authorization", bearer(UserRole.PROCUREMENT_STAFF))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(get("/api/v1/supplier-materials").queryParam("supplierId", supplier.getId().toString()).queryParam("status", "INACTIVE").header("Authorization", bearer(UserRole.PROCUREMENT_MANAGER))).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(id));
        mockMvc.perform(patch("/api/v1/supplier-materials/{id}", id).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"supplierId\":99}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(supplierMaterialRepository.findById(id)).isPresent();
    }
    @Test void appliesAuthenticationAndInactiveParentValidation() throws Exception {
        Supplier supplier = supplierRepository.saveAndFlush(Supplier.create("SUP-SM-I-" + System.nanoTime(), "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        Material material = materialRepository.saveAndFlush(Material.create("MAT-SM-I-" + System.nanoTime(), "Material", BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        String body = "{\"supplierId\":" + supplier.getId() + ",\"materialId\":" + material.getId() + ",\"unitPrice\":0,\"moq\":1,\"leadTimeDays\":0,\"currency\":\"TWD\"}";
        mockMvc.perform(post("/api/v1/supplier-materials").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.PROCUREMENT_STAFF)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        supplier.update(null, null, null, null, SupplierStatus.INACTIVE, clock.instant()); supplierRepository.saveAndFlush(supplier);
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
    }
    @Test void enforcesRequestContractAndRoleMatrix() throws Exception {
        Supplier supplier = activeSupplier(); Material material = activeMaterial(); String valid = body(supplier, material, 0, 1, null, 0, "TWD");
        mockMvc.perform(get("/api/v1/supplier-materials")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/supplier-materials").contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isUnauthorized());
        for (UserRole role : new UserRole[] {UserRole.PRODUCTION_PLANNER, UserRole.WAREHOUSE_STAFF}) mockMvc.perform(get("/api/v1/supplier-materials").header("Authorization", bearer(role))).andExpect(status().isForbidden());
        for (UserRole role : new UserRole[] {UserRole.PROCUREMENT_STAFF, UserRole.PROCUREMENT_MANAGER, UserRole.ADMIN}) mockMvc.perform(get("/api/v1/supplier-materials").header("Authorization", bearer(role))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.PROCUREMENT_MANAGER)).contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(supplier, material, -1, 1, null, 0, "TWD"))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(supplier, material, 0, 0, null, 0, "TWD"))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(supplier, material, 0, 1, 0, 0, "TWD"))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(supplier, material, 0, 1, null, -1, "TWD"))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(supplier, material, 0, 1, null, 0, "USD"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/supplier-materials/{id}", 999999L).header("Authorization", bearer(UserRole.ADMIN))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_MATERIAL_NOT_FOUND"));
    }
    @Test void rejectsMissingOrInactiveParentsAndImmutablePatchFields() throws Exception {
        Supplier supplier = activeSupplier(); Material material = activeMaterial();
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(999999L, material.getId(), 0, 1, null, 0, "TWD"))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(supplier.getId(), 999999L, 0, 1, null, 0, "TWD"))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MATERIAL_NOT_FOUND"));
        supplier.update(null, null, null, null, SupplierStatus.INACTIVE, clock.instant()); supplierRepository.saveAndFlush(supplier);
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(supplier, material, 0, 1, null, 0, "TWD"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
        Supplier active = activeSupplier(); material.update(null, null, MaterialStatus.INACTIVE, clock.instant()); materialRepository.saveAndFlush(material);
        mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(active, material, 0, 1, null, 0, "TWD"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MATERIAL_INACTIVE"));
    }
    @Test void patchPreservesFieldsLifecycleAndFilters() throws Exception {
        Supplier supplier = activeSupplier(); Material material = activeMaterial(); long id = create(supplier, material);
        mockMvc.perform(patch("/api/v1/supplier-materials/{id}", id).header("Authorization", bearer(UserRole.PROCUREMENT_STAFF)).contentType(MediaType.APPLICATION_JSON).content("{\"unitPrice\":9}")).andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/supplier-materials/{id}", id).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"unitPrice\":9}")).andExpect(status().isOk()).andExpect(jsonPath("$.moq").value(1)).andExpect(jsonPath("$.currency").value("TWD"));
        for (String field : new String[] {"supplierId","materialId","currency","id"}) mockMvc.perform(patch("/api/v1/supplier-materials/{id}", id).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"" + field + "\":1}")).andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/supplier-materials/{id}", id).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}")).andExpect(status().isOk());
        supplier.update(null, null, null, null, SupplierStatus.INACTIVE, clock.instant()); supplierRepository.saveAndFlush(supplier);
        mockMvc.perform(patch("/api/v1/supplier-materials/{id}", id).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
        supplier.update(null, null, null, null, SupplierStatus.ACTIVE, clock.instant()); supplierRepository.saveAndFlush(supplier);
        mockMvc.perform(patch("/api/v1/supplier-materials/{id}", id).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/supplier-materials").queryParam("supplierId", supplier.getId().toString()).queryParam("materialId", material.getId().toString()).queryParam("status", "ACTIVE").queryParam("page", "0").queryParam("size", "1").header("Authorization", bearer(UserRole.ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.size").value(1));
    }
    private Supplier activeSupplier() { return supplierRepository.saveAndFlush(Supplier.create("SUP-SM-" + System.nanoTime(), "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant())); }
    private Material activeMaterial() { return materialRepository.saveAndFlush(Material.create("MAT-SM-" + System.nanoTime(), "Material", BaseUnit.KG, BigDecimal.ZERO, clock.instant())); }
    private long create(Supplier supplier, Material material) throws Exception { MvcResult result = mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body(supplier, material, 0, 1, null, 0, "TWD"))).andExpect(status().isCreated()).andReturn(); return ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue(); }
    private String body(Supplier supplier, Material material, int price, int moq, Integer pack, int lead, String currency) { return body(supplier.getId(), material.getId(), price, moq, pack, lead, currency); }
    private String body(long supplierId, long materialId, int price, int moq, Integer pack, int lead, String currency) { return "{\"supplierId\":" + supplierId + ",\"materialId\":" + materialId + ",\"unitPrice\":" + price + ",\"moq\":" + moq + ",\"packageMultiple\":" + (pack == null ? "null" : pack) + ",\"leadTimeDays\":" + lead + ",\"currency\":\"" + currency + "\"}"; }
    private String bearer(UserRole role) {
        User principal = userRepository.saveAndFlush(User.create("supplier-material-auth-" + UUID.randomUUID(), "hash",
                role.name(), role, clock.instant()));
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(principal.getId(), principal.getUsername(), principal.getRole()));
    }
}

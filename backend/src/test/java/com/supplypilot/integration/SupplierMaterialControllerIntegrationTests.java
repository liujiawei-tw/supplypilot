package com.supplypilot.integration;

import com.supplypilot.material.domain.*;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.*;
import com.supplypilot.supplier.domain.*;
import com.supplypilot.supplier.repository.*;
import com.supplypilot.user.domain.UserRole;
import java.math.BigDecimal;
import java.time.Clock;
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
    MockMvc mockMvc; JwtTokenService tokens;
    @BeforeEach void setUp() { mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build(); tokens = new JwtTokenService(jwtProperties, clock); }
    @Test void adminCreatesListsUpdatesAndRetainsInactiveRecord() throws Exception {
        Supplier supplier = supplierRepository.saveAndFlush(Supplier.create("SUP-SM-" + System.nanoTime(), "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        Material material = materialRepository.saveAndFlush(Material.create("MAT-SM-" + System.nanoTime(), "Material", BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        String body = "{\"supplierId\":" + supplier.getId() + ",\"materialId\":" + material.getId() + ",\"unitPrice\":0,\"moq\":1,\"leadTimeDays\":0,\"currency\":\"TWD\"}";
        MvcResult result = mockMvc.perform(post("/api/v1/supplier-materials").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn();
        long id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
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
    private String bearer(UserRole role) { return "Bearer " + tokens.generateToken(new AuthenticatedUser(7000L + role.ordinal(), role.name(), role)); }
}

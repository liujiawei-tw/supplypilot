package com.supplypilot.integration;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SupplierControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private SupplierRepository supplierRepository;
    private MockMvc mockMvc; private JwtTokenService tokens;
    @BeforeEach void setUp() { mockMvc=MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build(); tokens=new JwtTokenService(jwtProperties, clock); }
    @Test void adminCanCreateAndUpdateSupplierWhileProcurementRolesCanRead() throws Exception {
        String code=code();
        mockMvc.perform(post("/api/v1/suppliers").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(json(code)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"));
        Supplier supplier=supplierRepository.findAll().stream().filter(item -> item.getCode().equals(code)).findFirst().orElseThrow();
        mockMvc.perform(patch("/api/v1/suppliers/{id}", supplier.getId()).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"country\":\"\",\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.country").value(org.hamcrest.Matchers.nullValue())).andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(get("/api/v1/suppliers/{id}", supplier.getId()).header("Authorization", bearer(UserRole.PROCUREMENT_STAFF))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/suppliers?status=INACTIVE&sort=code,desc").header("Authorization", bearer(UserRole.PROCUREMENT_MANAGER))).andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
    }
    @Test void enforcesAuthorizationValidationAndImmutableFields() throws Exception {
        mockMvc.perform(post("/api/v1/suppliers").contentType(MediaType.APPLICATION_JSON).content(json(code()))).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/suppliers").header("Authorization", bearer(UserRole.PROCUREMENT_STAFF)).contentType(MediaType.APPLICATION_JSON).content(json(code()))).andExpect(status().isForbidden());
        Supplier supplier=supplierRepository.saveAndFlush(Supplier.create(code(), "Original", "TW", BigDecimal.ONE, BigDecimal.ONE, clock.instant()));
        mockMvc.perform(patch("/api/v1/suppliers/{id}", supplier.getId()).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"NOPE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/suppliers").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"BAD\",\"name\":\"Bad\",\"reliabilityScore\":101,\"qualityScore\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/suppliers/{id}", 999999999L).header("Authorization", bearer(UserRole.ADMIN))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
        assertThat(supplierRepository.findById(supplier.getId()).orElseThrow().getCode()).isEqualTo(supplier.getCode());
    }
    private String code() { return "SUP-HTTP-"+UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    private String json(String code) { return "{\"code\":\""+code+"\",\"name\":\"Foods\",\"country\":\"TW\",\"reliabilityScore\":92.5,\"qualityScore\":95}"; }
    private String bearer(UserRole role) { return "Bearer "+tokens.generateToken(new AuthenticatedUser(1000L+role.ordinal(), role.name(), role)); }
}

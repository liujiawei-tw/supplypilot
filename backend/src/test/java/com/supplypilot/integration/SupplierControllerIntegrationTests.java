package com.supplypilot.integration;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SupplierControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private UserRepository userRepository;
    private MockMvc mockMvc;
    private JwtTokenService tokens;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tokens = new JwtTokenService(jwtProperties, clock);
    }

    @Test
    void adminCanCreateSupplierWithCompleteResponseAndBoundaryScores() throws Exception {
        String code = code();
        mockMvc.perform(post("/api/v1/suppliers").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(json(code, "Foods", "TW", 0, 100)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber()).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.name").value("Foods")).andExpect(jsonPath("$.country").value("TW"))
                .andExpect(jsonPath("$.reliabilityScore").value(0)).andExpect(jsonPath("$.qualityScore").value(100))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.createdAt").exists()).andExpect(jsonPath("$.updatedAt").exists());
        Supplier persisted = byCode(code);
        assertThat(persisted.getReliabilityScore()).isEqualByComparingTo("0");
        assertThat(persisted.getQualityScore()).isEqualByComparingTo("100");
    }

    @Test
    void enforcesAuthenticationAndRoleMatrix() throws Exception {
        Supplier supplier = save("Original", "TW", 1, 1, SupplierStatus.ACTIVE);
        mockMvc.perform(get("/api/v1/suppliers")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/v1/suppliers").contentType(MediaType.APPLICATION_JSON).content(json(code(), "New", "TW", 1, 1))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        for (UserRole role : new UserRole[] {
                UserRole.PRODUCTION_PLANNER,
                UserRole.PROCUREMENT_STAFF,
                UserRole.PROCUREMENT_MANAGER,
                UserRole.WAREHOUSE_STAFF
        }) {
            mockMvc.perform(post("/api/v1/suppliers").header("Authorization", bearer(role)).contentType(MediaType.APPLICATION_JSON).content(json(code(), "New", "TW", 1, 1))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mockMvc.perform(patch("/api/v1/suppliers/{id}", supplier.getId()).header("Authorization", bearer(role)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Forbidden\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        for (UserRole role : new UserRole[] {UserRole.PROCUREMENT_STAFF, UserRole.PROCUREMENT_MANAGER, UserRole.ADMIN}) {
            mockMvc.perform(get("/api/v1/suppliers/{id}", supplier.getId()).header("Authorization", bearer(role))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(supplier.getId()));
        }
        for (UserRole role : new UserRole[] {UserRole.PRODUCTION_PLANNER, UserRole.WAREHOUSE_STAFF}) {
            mockMvc.perform(get("/api/v1/suppliers").header("Authorization", bearer(role)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        assertThat(supplierRepository.findById(supplier.getId()).orElseThrow().getName()).isEqualTo("Original");
    }

    @Test
    void returnsExpectedErrorsForNotFoundDuplicateInvalidScoresAndImmutableFields() throws Exception {
        mockMvc.perform(get("/api/v1/suppliers/{id}", 999999999L).header("Authorization", bearer(UserRole.ADMIN))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
        String duplicate = code();
        mockMvc.perform(post("/api/v1/suppliers").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(json(duplicate, "One", "TW", 1, 1))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/suppliers").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(json(duplicate, "Two", "TW", 1, 1))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        assertThat(supplierRepository.findAll().stream().filter(item -> item.getCode().equals(duplicate))).hasSize(1);
        for (String body : new String[] {json(code(), "Bad", "TW", -1, 0), json(code(), "Bad", "TW", 0, 101)}) {
            mockMvc.perform(post("/api/v1/suppliers").header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        Supplier supplier = save("Original", "TW", 50, 50, SupplierStatus.ACTIVE);
        for (String body : new String[] {"{\"code\":\"NOPE\"}", "{\"id\":999}", "{\"reliabilityScore\":-0.01}", "{\"qualityScore\":100.01}"}) {
            mockMvc.perform(patch("/api/v1/suppliers/{id}", supplier.getId()).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        Supplier unchanged = supplierRepository.findById(supplier.getId()).orElseThrow();
        assertThat(unchanged.getCode()).isEqualTo(supplier.getCode());
        assertThat(unchanged.getName()).isEqualTo("Original");
        assertThat(unchanged.getReliabilityScore()).isEqualByComparingTo("50");
        assertThat(unchanged.getQualityScore()).isEqualByComparingTo("50");
    }

    @Test
    void patchPreservesOmittedAndNullFieldsClearsCountryAndPersistsLifecycle() throws Exception {
        Supplier supplier = save("Original", "TW", 50, 50, SupplierStatus.ACTIVE);
        mockMvc.perform(patch("/api/v1/suppliers/{id}", supplier.getId()).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Updated\",\"reliabilityScore\":0,\"qualityScore\":100}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.country").value("TW")).andExpect(jsonPath("$.reliabilityScore").value(0)).andExpect(jsonPath("$.qualityScore").value(100));
        mockMvc.perform(patch("/api/v1/suppliers/{id}", supplier.getId()).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"country\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.country").value("TW"));
        mockMvc.perform(patch("/api/v1/suppliers/{id}", supplier.getId()).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"country\":\"\",\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.country").value(nullValue())).andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(get("/api/v1/suppliers/{id}", supplier.getId()).header("Authorization", bearer(UserRole.PROCUREMENT_STAFF))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE")).andExpect(jsonPath("$.name").value("Updated"));
        mockMvc.perform(patch("/api/v1/suppliers/{id}", supplier.getId()).header("Authorization", bearer(UserRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        Supplier persisted = supplierRepository.findById(supplier.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(SupplierStatus.ACTIVE);
        assertThat(persisted.getCountry()).isNull();
    }

    @Test
    void listFiltersPaginatesAndSortsIncludingInactiveSuppliers() throws Exception {
        String marker = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Supplier alpha = saveWithCode("SUP-SORT-" + marker + "-A", "Filter " + marker + " Alpha", "TW", SupplierStatus.INACTIVE);
        Supplier bravo = saveWithCode("SUP-SORT-" + marker + "-B", "Filter " + marker + " Bravo", "US", SupplierStatus.INACTIVE);
        saveWithCode("SUP-SORT-" + marker + "-C", "Filter " + marker + " Charlie", "JP", SupplierStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/suppliers")
                        .queryParam("status", "INACTIVE")
                        .queryParam("code", "SUP-SORT-" + marker)
                        .queryParam("name", "Filter " + marker)
                        .queryParam("size", "1")
                        .queryParam("sort", "name,desc")
                        .queryParam("page", "0")
                        .header("Authorization", bearer(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(bravo.getId()))
                .andExpect(jsonPath("$.content[0].status").value("INACTIVE"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1));
        mockMvc.perform(get("/api/v1/suppliers")
                        .queryParam("status", "INACTIVE")
                        .queryParam("code", "SUP-SORT-" + marker)
                        .queryParam("name", "Filter " + marker)
                        .queryParam("size", "1")
                        .queryParam("sort", "name,desc")
                        .queryParam("page", "1")
                        .header("Authorization", bearer(UserRole.PROCUREMENT_MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(alpha.getId()))
                .andExpect(jsonPath("$.page").value(1));
    }

    private Supplier save(String name, String country, int reliability, int quality, SupplierStatus status) {
        Supplier supplier = Supplier.create(code(), name, country, BigDecimal.valueOf(reliability), BigDecimal.valueOf(quality), clock.instant());
        if (status == SupplierStatus.INACTIVE) supplier.update(null, null, null, null, status, clock.instant());
        return supplierRepository.saveAndFlush(supplier);
    }

    private Supplier saveWithCode(String code, String name, String country, SupplierStatus status) {
        Supplier supplier = Supplier.create(code, name, country, new BigDecimal("50"), new BigDecimal("50"), clock.instant());
        if (status == SupplierStatus.INACTIVE) supplier.update(null, null, null, null, status, clock.instant());
        return supplierRepository.saveAndFlush(supplier);
    }
    private Supplier byCode(String code) { return supplierRepository.findAll().stream().filter(supplier -> supplier.getCode().equals(code)).findFirst().orElseThrow(); }
    private String code() { return "SUP-HTTP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    private String json(String code, String name, String country, int reliability, int quality) { return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"country\":\"" + country + "\",\"reliabilityScore\":" + reliability + ",\"qualityScore\":" + quality + "}"; }
    private String bearer(UserRole role) {
        User principal = userRepository.saveAndFlush(User.create("supplier-auth-" + UUID.randomUUID(), "hash",
                role.name(), role, clock.instant()));
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(principal.getId(), principal.getUsername(), principal.getRole()));
    }
}

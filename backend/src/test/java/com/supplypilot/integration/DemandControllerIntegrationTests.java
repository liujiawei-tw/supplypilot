package com.supplypilot.integration;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import com.supplypilot.demand.domain.Demand;
import com.supplypilot.demand.repository.DemandRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DemandControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext webApplicationContext; @Autowired private JwtProperties jwtProperties; @Autowired private Clock clock;
    @Autowired private MaterialRepository materialRepository; @Autowired private UserRepository userRepository; @Autowired private DemandRepository demandRepository;
    private MockMvc mockMvc; private JwtTokenService tokens;

    @BeforeEach void setUp() { mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(SecurityMockMvcConfigurers.springSecurity()).build(); tokens = new JwtTokenService(jwtProperties, clock); }

    @Test void postAllowsPlannerAndAdminAndRejectsOtherRolesAndAnonymous() throws Exception {
        Material material = material(); User planner = user(UserRole.PRODUCTION_PLANNER); User admin = user(UserRole.ADMIN);
        User buyer = user(UserRole.PROCUREMENT_STAFF); User manager = user(UserRole.PROCUREMENT_MANAGER); User warehouse = user(UserRole.WAREHOUSE_STAFF);
        create(material, planner).andExpect(status().isCreated()); create(material, admin).andExpect(status().isCreated());
        for (User denied : java.util.List.of(buyer, manager, warehouse)) create(material, denied).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/demands").contentType(MediaType.APPLICATION_JSON).content(payload(material))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test void readsAllowPlannerManagerAndAdminAndRejectBuyerWarehouseAndAnonymous() throws Exception {
        Material material = material(); User planner = user(UserRole.PRODUCTION_PLANNER); User manager = user(UserRole.PROCUREMENT_MANAGER); User admin = user(UserRole.ADMIN);
        User buyer = user(UserRole.PROCUREMENT_STAFF); User warehouse = user(UserRole.WAREHOUSE_STAFF); Demand demand = demand(material, planner, LocalDate.of(2026, 10, 8));
        for (User allowed : java.util.List.of(planner, manager, admin)) { mockMvc.perform(get("/api/v1/demands").header("Authorization", bearer(allowed))).andExpect(status().isOk()); mockMvc.perform(get("/api/v1/demands/{id}", demand.getId()).header("Authorization", bearer(allowed))).andExpect(status().isOk()); }
        for (User denied : java.util.List.of(buyer, warehouse)) { mockMvc.perform(get("/api/v1/demands").header("Authorization", bearer(denied))).andExpect(status().isForbidden()); mockMvc.perform(get("/api/v1/demands/{id}", demand.getId()).header("Authorization", bearer(denied))).andExpect(status().isForbidden()); }
        mockMvc.perform(get("/api/v1/demands")).andExpect(status().isUnauthorized()); mockMvc.perform(get("/api/v1/demands/{id}", demand.getId())).andExpect(status().isUnauthorized());
    }

    @Test void patchAndCancelAllowPlannerAndAdminAndRejectOtherRolesAndAnonymous() throws Exception {
        Material material = material(); User planner = user(UserRole.PRODUCTION_PLANNER); User admin = user(UserRole.ADMIN);
        User buyer = user(UserRole.PROCUREMENT_STAFF); User manager = user(UserRole.PROCUREMENT_MANAGER); User warehouse = user(UserRole.WAREHOUSE_STAFF);
        Demand plannerDemand = demand(material, planner, LocalDate.of(2026, 10, 8)); Demand adminDemand = demand(material, admin, LocalDate.of(2026, 10, 8));
        mockMvc.perform(patch("/api/v1/demands/{id}", plannerDemand.getId()).header("Authorization", bearer(planner)).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}")).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/demands/{id}", adminDemand.getId()).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}")).andExpect(status().isOk());
        for (User denied : java.util.List.of(buyer, manager, warehouse)) { Demand target = demand(material, planner, LocalDate.of(2026, 10, 8)); mockMvc.perform(patch("/api/v1/demands/{id}", target.getId()).header("Authorization", bearer(denied)).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}")).andExpect(status().isForbidden()); mockMvc.perform(post("/api/v1/demands/{id}/cancel", target.getId()).header("Authorization", bearer(denied))).andExpect(status().isForbidden()); }
        Demand cancelByPlanner = demand(material, planner, LocalDate.of(2026, 10, 8)); Demand cancelByAdmin = demand(material, admin, LocalDate.of(2026, 10, 8));
        mockMvc.perform(post("/api/v1/demands/{id}/cancel", cancelByPlanner.getId()).header("Authorization", bearer(planner))).andExpect(status().isOk()); mockMvc.perform(post("/api/v1/demands/{id}/cancel", cancelByAdmin.getId()).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/demands/{id}", plannerDemand.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}")).andExpect(status().isUnauthorized()); mockMvc.perform(post("/api/v1/demands/{id}/cancel", plannerDemand.getId())).andExpect(status().isUnauthorized());
    }

    @Test void listsDemandsWithExactInclusiveAndCombinedFiltersAndValidatesPagingAndSort() throws Exception {
        Material first = material(); Material second = material(); User planner = user(UserRole.PRODUCTION_PLANNER);
        Demand firstStart = demand(first, planner, LocalDate.of(2026, 10, 8)); Demand firstEnd = demand(first, planner, LocalDate.of(2026, 10, 10)); firstEnd.cancel(clock.instant()); demandRepository.saveAndFlush(firstEnd); demand(second, planner, LocalDate.of(2026, 10, 9)); String auth = bearer(planner);
        mockMvc.perform(get("/api/v1/demands").param("materialId", first.getId().toString()).header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/demands").param("status", "CANCELLED").header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(firstEnd.getId()));
        mockMvc.perform(get("/api/v1/demands").param("requiredFrom", "2026-10-08").param("requiredTo", "2026-10-08").header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(firstStart.getId()));
        mockMvc.perform(get("/api/v1/demands").param("materialId", first.getId().toString()).param("status", "CANCELLED").param("requiredFrom", "2026-10-10").param("requiredTo", "2026-10-10").header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/demands").param("requiredFrom", "2026-10-10").param("requiredTo", "2026-10-08").header("Authorization", auth)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        mockMvc.perform(get("/api/v1/demands").param("page", "0").param("size", "1").param("sort", "requiredDate,desc").header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1));
        mockMvc.perform(get("/api/v1/demands").param("page", "-1").header("Authorization", auth)).andExpect(status().isBadRequest()); mockMvc.perform(get("/api/v1/demands").param("size", "101").header("Authorization", auth)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/demands").param("sort", "hackerField,asc").header("Authorization", auth)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private org.springframework.test.web.servlet.ResultActions create(Material material, User user) throws Exception { return mockMvc.perform(post("/api/v1/demands").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(payload(material))); }
    private String payload(Material material) { return "{\"materialId\":" + material.getId() + ",\"quantity\":1,\"requiredDate\":\"2026-10-08\"}"; }
    private Material material() { return materialRepository.saveAndFlush(Material.create("MAT-D-" + suffix(), "Tea", BaseUnit.KG, BigDecimal.ZERO, clock.instant())); }
    private User user(UserRole role) { return userRepository.saveAndFlush(User.create("demand-" + suffix(), "hash", role.name(), role, clock.instant())); }
    private Demand demand(Material material, User user, LocalDate date) { return demandRepository.saveAndFlush(Demand.create(material, BigDecimal.ONE, date, "Note", user, clock.instant())); }
    private String bearer(User user) { return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole())); }
    private String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
}

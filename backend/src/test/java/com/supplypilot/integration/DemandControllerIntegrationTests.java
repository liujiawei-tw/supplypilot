package com.supplypilot.integration;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import com.supplypilot.demand.domain.Demand;
import com.supplypilot.demand.repository.DemandRepository;
import com.supplypilot.material.domain.*;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.*;
import com.supplypilot.user.domain.*;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DemandControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired WebApplicationContext webApplicationContext; @Autowired JwtProperties jwtProperties; @Autowired Clock clock;
    @Autowired MaterialRepository materialRepository; @Autowired UserRepository userRepository; @Autowired DemandRepository demandRepository;
    private MockMvc mockMvc; private JwtTokenService tokens;
    @BeforeEach void setUp() { mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(SecurityMockMvcConfigurers.springSecurity()).build(); tokens = new JwtTokenService(jwtProperties, clock); }

    @Test void plannerCanCreateAndManagerCanReadButCannotWrite() throws Exception {
        Material material = material(); User planner = user(UserRole.PRODUCTION_PLANNER); User manager = user(UserRole.PROCUREMENT_MANAGER);
        mockMvc.perform(post("/api/v1/demands").header("Authorization", bearer(planner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"materialId\":" + material.getId() + ",\"quantity\":400,\"requiredDate\":\"2026-10-08\",\"note\":\"Promotion\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.createdBy").value(planner.getId()));
        mockMvc.perform(get("/api/v1/demands").header("Authorization", bearer(manager))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mockMvc.perform(post("/api/v1/demands").header("Authorization", bearer(manager)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialId\":" + material.getId() + ",\"quantity\":1,\"requiredDate\":\"2026-10-08\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test void enforcesAuthenticationValidationAndImmutablePatchFields() throws Exception {
        Material material = material(); User planner = user(UserRole.PRODUCTION_PLANNER);
        mockMvc.perform(get("/api/v1/demands")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/v1/demands").header("Authorization", bearer(planner)).contentType(MediaType.APPLICATION_JSON).content("{\"materialId\":" + material.getId() + ",\"quantity\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        Demand demand = demand(material, planner, LocalDate.of(2026, 10, 8));
        mockMvc.perform(patch("/api/v1/demands/{id}", demand.getId()).header("Authorization", bearer(planner)).contentType(MediaType.APPLICATION_JSON).content("{\"materialId\":99}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch("/api/v1/demands/{id}", demand.getId()).header("Authorization", bearer(planner)).contentType(MediaType.APPLICATION_JSON).content("{\"note\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.note").doesNotExist());
    }

    @Test void cancelsActiveDemandAndRejectsPastOrCancelledLifecycle() throws Exception {
        Material material = material(); User planner = user(UserRole.PRODUCTION_PLANNER); Demand active = demand(material, planner, LocalDate.of(2026, 10, 8));
        mockMvc.perform(post("/api/v1/demands/{id}/cancel", active.getId()).header("Authorization", bearer(planner))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/v1/demands/{id}/cancel", active.getId()).header("Authorization", bearer(planner))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DEMAND_NOT_ACTIVE"));
        Demand past = demand(material, planner, LocalDate.of(2026, 10, 7));
        mockMvc.perform(patch("/api/v1/demands/{id}", past.getId()).header("Authorization", bearer(planner)).contentType(MediaType.APPLICATION_JSON).content("{\"requiredDate\":\"2026-10-09\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }
    private Material material() { return materialRepository.saveAndFlush(Material.create("MAT-D-" + suffix(), "Tea", BaseUnit.KG, BigDecimal.ZERO, clock.instant())); }
    private User user(UserRole role) { return userRepository.saveAndFlush(User.create("demand-" + suffix(), "hash", role.name(), role, clock.instant())); }
    private Demand demand(Material material, User user, LocalDate date) { return demandRepository.saveAndFlush(Demand.create(material, BigDecimal.ONE, date, "Note", user, clock.instant())); }
    private String bearer(User user) { return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole())); }
    private String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
}

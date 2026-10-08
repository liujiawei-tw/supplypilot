package com.supplypilot.integration;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MaterialControllerIntegrationTests extends AbstractIntegrationTest {
    private static final Pattern ID_PATTERN = Pattern.compile("\\\"id\\\":\\s*(\\d+)");

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private MaterialRepository materialRepository;

    private MockMvc mockMvc;
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        jwtTokenService = new JwtTokenService(jwtProperties, clock);
    }

    @Test
    void adminCanCreateValidMaterial() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/v1/materials").header("Authorization", bearerToken(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(createJson(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.baseUnit").value("KG"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void nonAdminCannotCreateOrPatchMaterial() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/v1/materials").header("Authorization", bearerToken(UserRole.PRODUCTION_PLANNER))
                        .contentType(MediaType.APPLICATION_JSON).content(createJson(code)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        Material material = saveMaterial();
        mockMvc.perform(patch("/api/v1/materials/{id}", material.getId())
                        .header("Authorization", bearerToken(UserRole.PRODUCTION_PLANNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Not Allowed\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void unauthenticatedMaterialRequestsReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/materials").contentType(MediaType.APPLICATION_JSON).content(createJson(uniqueCode())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void getUnknownMaterialReturnsMaterialNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/materials/{id}", 999999999L).header("Authorization", bearerToken(UserRole.ADMIN)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MATERIAL_NOT_FOUND"));
    }

    @Test
    void invalidCreateDtoAndBaseUnitReturnValidationFailed() throws Exception {
        mockMvc.perform(post("/api/v1/materials").header("Authorization", bearerToken(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"\",\"name\":\"\",\"safetyStock\":-1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/materials").header("Authorization", bearerToken(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + uniqueCode() + "\",\"name\":\"Berry\",\"baseUnit\":\"TON\",\"safetyStock\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void patchRejectsImmutableFieldsWithoutChangingMaterial() throws Exception {
        Material material = saveMaterial();
        mockMvc.perform(patch("/api/v1/materials/{id}", material.getId()).header("Authorization", bearerToken(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"CHANGED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch("/api/v1/materials/{id}", material.getId()).header("Authorization", bearerToken(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"baseUnit\":\"L\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Material persisted = materialRepository.findById(material.getId()).orElseThrow();
        assertThat(persisted.getCode()).isEqualTo(material.getCode());
        assertThat(persisted.getBaseUnit()).isEqualTo(BaseUnit.KG);
    }

    @Test
    void validPatchUpdatesOnlyEditableFields() throws Exception {
        Material material = saveMaterial();
        mockMvc.perform(patch("/api/v1/materials/{id}", material.getId()).header("Authorization", bearerToken(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Berry\",\"safetyStock\":12.5,\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Updated Berry"))
                .andExpect(jsonPath("$.safetyStock").value(12.5)).andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    void duplicateMaterialCodeReturnsDuplicateCode() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/v1/materials").header("Authorization", bearerToken(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(createJson(code)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/materials").header("Authorization", bearerToken(UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(createJson(code)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
    }

    private Material saveMaterial() {
        return materialRepository.saveAndFlush(Material.create(uniqueCode(), "Original Berry", BaseUnit.KG,
                BigDecimal.ONE, clock.instant()));
    }

    private String uniqueCode() { return "MAT-HTTP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    private String createJson(String code) { return "{\"code\":\"" + code + "\",\"name\":\"Berry\",\"baseUnit\":\"KG\",\"safetyStock\":0}"; }
    private String bearerToken(UserRole role) {
        return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(1000L + role.ordinal(), role.name(), role));
    }
}

package com.supplypilot.integration;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.UserRole;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventoryControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        jwtTokenService = new JwtTokenService(jwtProperties, clock);
    }

    @Test
    void listAppliesFiltersPaginationAndPublicResponseContract() throws Exception {
        Material first = materialWithInventory("MAT-INV-001", "Apple");
        materialWithInventory("MAT-INV-002", "Banana");
        materialWithInventory("RAW-INV-001", "Sugar");

        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN))
                        .param("materialId", first.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].materialId").value(first.getId()))
                .andExpect(jsonPath("$.content[0].materialCode").value(first.getCode()))
                .andExpect(jsonPath("$.content[0].materialName").value("Apple"))
                .andExpect(jsonPath("$.content[0].quantity").value(0))
                .andExpect(jsonPath("$.content[0].unit").value("KG"))
                .andExpect(jsonPath("$.content[0].updatedAt").exists())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.totalPages").value(1));

        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN))
                        .param("materialCode", "MAT-INV").param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void validatesPaginationAndReturnsEmptyPageForUnknownListMaterial() throws Exception {
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN)).param("size", "100"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN)).param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN)).param("page", "-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN)).param("size", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.ADMIN)).param("materialId", "999999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void detailDistinguishesMissingMaterialAndInventory() throws Exception {
        Material material = materialWithInventory("MAT-DETAIL", "Detail material");
        mockMvc.perform(get("/api/v1/inventory/{materialId}", material.getId()).header("Authorization", bearer(UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.materialId").value(material.getId()));
        mockMvc.perform(get("/api/v1/inventory/{materialId}", 999999999L).header("Authorization", bearer(UserRole.ADMIN)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MATERIAL_NOT_FOUND"));

        Material missingInventory = material("MAT-MISSING-INV", "Missing inventory");
        jdbcTemplate.update("DELETE FROM inventory WHERE material_id = ?", missingInventory.getId());
        mockMvc.perform(get("/api/v1/inventory/{materialId}", missingInventory.getId()).header("Authorization", bearer(UserRole.ADMIN)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
    }

    @Test
    void requiresAuthenticationAndRestrictsAccessToInventoryRoles() throws Exception {
        mockMvc.perform(get("/api/v1/inventory")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        for (UserRole role : new UserRole[] {UserRole.PRODUCTION_PLANNER, UserRole.PROCUREMENT_STAFF, UserRole.WAREHOUSE_STAFF, UserRole.ADMIN}) {
            mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(role))).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer(UserRole.PROCUREMENT_MANAGER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private Material materialWithInventory(String code, String name) {
        Material material = material(code, name);
        inventoryRepository.saveAndFlush(Inventory.create(material, clock.instant()));
        return material;
    }

    private Material material(String code, String name) {
        return materialRepository.saveAndFlush(Material.create(code + "-" + UUID.randomUUID().toString().substring(0, 8), name, BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
    }

    private String bearer(UserRole role) {
        return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(1000L + role.ordinal(), role.name(), role));
    }
}

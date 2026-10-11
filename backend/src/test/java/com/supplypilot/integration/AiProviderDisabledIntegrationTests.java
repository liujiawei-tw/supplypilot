package com.supplypilot.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

class AiProviderDisabledIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private UserRepository users;
    @Autowired private MaterialRepository materials;
    @Autowired private InventoryRepository inventories;

    private MockMvc mockMvc;
    private JwtTokenService tokens;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        tokens = new JwtTokenService(jwtProperties, clock);
    }

    @Test
    void missingProviderReturnsAi503WhileCoreInventoryApiRemainsAvailable() throws Exception {
        User staff = users.saveAndFlush(User.create("ai-disabled-" + suffix(), "hash",
                UserRole.PROCUREMENT_STAFF.name(), UserRole.PROCUREMENT_STAFF, clock.instant()));
        Material material = materials.saveAndFlush(Material.create(
                "MAT-" + suffix(), "Core material", BaseUnit.KG, BigDecimal.ZERO, clock.instant()));
        Inventory inventory = Inventory.create(material, clock.instant());
        inventory.adjust(new BigDecimal("25"), clock.instant());
        inventories.saveAndFlush(inventory);
        String bearer = "Bearer " + tokens.generateToken(
                new AuthenticatedUser(staff.getId(), staff.getUsername(), staff.getRole()));

        mockMvc.perform(post("/api/v1/ai/query").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"查詢 " + material.getCode() + " 庫存\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));

        mockMvc.perform(get("/api/v1/inventory").header("Authorization", bearer)
                        .param("materialCode", material.getCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].quantity").value(25));
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

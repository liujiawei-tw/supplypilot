package com.supplypilot.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import com.supplypilot.inventory.domain.StockTransaction;
import com.supplypilot.inventory.repository.StockTransactionRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class StockTransactionControllerIntegrationTests extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StockTransactionRepository transactionRepository;
    @Autowired private Clock clock;

    @Test
    void returnsLimitedReadProjectionAndEnforcesReadRoles() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Material material = materialRepository.saveAndFlush(Material.create("ST-" + suffix, "Transaction material", BaseUnit.PCS,
                BigDecimal.ZERO, clock.instant()));
        User creator = userRepository.saveAndFlush(User.create("stock-" + suffix, "hash", "倉管人員", UserRole.WAREHOUSE_STAFF, clock.instant()));
        StockTransaction transaction = transactionRepository.saveAndFlush(StockTransaction.manualAdjustment(
                material, new BigDecimal("-2"), "damaged", creator, clock.instant()));

        mockMvc.perform(get("/api/v1/stock-transactions/{id}", transaction.getId()).header("Authorization", bearer(UserRole.WAREHOUSE_STAFF)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(transaction.getId()))
                .andExpect(jsonPath("$.materialCode").value(material.getCode()))
                .andExpect(jsonPath("$.unit").value("PCS")).andExpect(jsonPath("$.quantity").value(-2))
                .andExpect(jsonPath("$.reason").value("damaged")).andExpect(jsonPath("$.purchaseOrderId").doesNotExist())
                .andExpect(jsonPath("$.createdByDisplayName").value("倉管人員"));
        mockMvc.perform(get("/api/v1/stock-transactions").header("Authorization", bearer(UserRole.PROCUREMENT_STAFF)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/stock-transactions/999999999" ).header("Authorization", bearer(UserRole.ADMIN)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STOCK_TRANSACTION_NOT_FOUND"));
    }

    private String bearer(UserRole role) {
        return "Bearer " + jwtTokenService.generateToken(new AuthenticatedUser(999L, "test-" + role, role));
    }
}

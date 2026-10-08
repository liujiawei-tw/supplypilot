package com.supplypilot.integration;

import java.math.BigDecimal;

import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.dto.request.CreateMaterialRequest;
import com.supplypilot.material.service.MaterialService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterialIntegrationTests extends AbstractIntegrationTest {
    @Autowired private MaterialService materialService;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @WithMockUser(roles = "ADMIN")
    void createsExactlyOnePersistedZeroInventoryWithMaterial() {
        var created = materialService.createMaterial(new CreateMaterialRequest(
                "MAT-INTEGRATION-OK", "Integration Berry", BaseUnit.KG, new BigDecimal("2.5")
        ));

        var inventory = inventoryRepository.findByMaterialId(created.id()).orElseThrow();
        assertThat(inventory.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(inventoryRepository.countByMaterialId(created.id())).isEqualTo(1);
        assertThat(inventory.getVersion()).isNotNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rollsBackMaterialWhenRealDatabaseRejectsInventoryPersistence() {
        jdbcTemplate.execute("ALTER TABLE inventory ADD CONSTRAINT chk_inventory_test_nonzero CHECK (quantity <> 0)");
        try {
            assertThatThrownBy(() -> materialService.createMaterial(new CreateMaterialRequest(
                    "MAT-INTEGRATION-ROLLBACK", "Rollback Berry", BaseUnit.KG, BigDecimal.ZERO
            ))).isInstanceOf(Exception.class);

            Integer materialCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM materials WHERE code = ?", Integer.class, "MAT-INTEGRATION-ROLLBACK");
            assertThat(materialCount).isZero();
        } finally {
            jdbcTemplate.execute("ALTER TABLE inventory DROP CHECK chk_inventory_test_nonzero");
        }
    }

    @Test
    @WithMockUser(roles = "PRODUCTION_PLANNER")
    void deniesMaterialWritesForNonAdminAtServiceBoundary() {
        assertThatThrownBy(() -> materialService.createMaterial(new CreateMaterialRequest(
                "MAT-NOT-ALLOWED", "Forbidden", BaseUnit.KG, BigDecimal.ZERO
        ))).isInstanceOf(AccessDeniedException.class);
    }
}

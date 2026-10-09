package com.supplypilot.demand.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.supplypilot.demand.domain.*;
import com.supplypilot.integration.AbstractIntegrationTest;
import com.supplypilot.material.domain.*;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.*;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class DemandRepositoryTests extends AbstractIntegrationTest {
    @Autowired DemandRepository demandRepository; @Autowired MaterialRepository materialRepository; @Autowired UserRepository userRepository; @Autowired JdbcTemplate jdbcTemplate;
    @Test void persistsDemandAndKeepsCancelledRow() {
        Material material = materialRepository.saveAndFlush(Material.create("MAT-DEMAND-REPO", "Tea", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT));
        User user = userRepository.saveAndFlush(User.create("demand-repo-user", "hash", "Planner", UserRole.PRODUCTION_PLANNER, TestDataConstants.TEST_INSTANT));
        Demand demand = demandRepository.saveAndFlush(Demand.create(material, new BigDecimal("12.5"), LocalDate.of(2026, 10, 8), "Note", user, TestDataConstants.TEST_INSTANT));
        demand.cancel(TestDataConstants.TEST_INSTANT.plusSeconds(1)); demandRepository.saveAndFlush(demand);
        Demand persisted = demandRepository.findById(demand.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(DemandStatus.CANCELLED); assertThat(persisted.getRequiredDate()).isEqualTo(LocalDate.of(2026, 10, 8)); assertThat(persisted.getCreatedBy().getId()).isEqualTo(user.getId());
    }
    @Test void databaseEnforcesPositiveQuantity() {
        Material material = materialRepository.saveAndFlush(Material.create("MAT-DEMAND-CHECK", "Tea", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT));
        User user = userRepository.saveAndFlush(User.create("demand-check-user", "hash", "Planner", UserRole.PRODUCTION_PLANNER, TestDataConstants.TEST_INSTANT));
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO demands (material_id, quantity, required_date, status, created_by, created_at, updated_at) VALUES (?, 0, ?, 'ACTIVE', ?, ?, ?)", material.getId(), LocalDate.of(2026, 10, 8), user.getId(), TestDataConstants.TEST_INSTANT, TestDataConstants.TEST_INSTANT)).isInstanceOf(DataIntegrityViolationException.class);
    }
}

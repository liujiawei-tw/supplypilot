package com.supplypilot.demand.repository;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import com.supplypilot.demand.domain.Demand;
import com.supplypilot.demand.domain.DemandStatus;
import com.supplypilot.integration.AbstractIntegrationTest;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;
import static org.assertj.core.api.Assertions.*;

class DemandRepositoryTests extends AbstractIntegrationTest {
    @Autowired private DemandRepository demandRepository; @Autowired private MaterialRepository materialRepository; @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate; @Autowired private EntityManager entityManager;

    @Test void persistsUpdatesAndKeepsCancelledRowAfterReload() {
        Material material = material("MAT-DEMAND-REPO"); User user = user("demand-repo-user");
        Demand demand = demandRepository.saveAndFlush(Demand.create(material, new BigDecimal("12.5"), LocalDate.of(2026, 10, 8), "Note", user, TestDataConstants.TEST_INSTANT));
        demand.update(new BigDecimal("15"), LocalDate.of(2026, 10, 9), null, true, LocalDate.of(2026, 10, 8), TestDataConstants.TEST_INSTANT.plusSeconds(1)); demand.cancel(TestDataConstants.TEST_INSTANT.plusSeconds(2)); demandRepository.saveAndFlush(demand);
        entityManager.clear(); Demand persisted = demandRepository.findById(demand.getId()).orElseThrow();
        assertThat(persisted.getQuantity()).isEqualByComparingTo("15"); assertThat(persisted.getRequiredDate()).isEqualTo(LocalDate.of(2026, 10, 9)); assertThat(persisted.getNote()).isNull(); assertThat(persisted.getStatus()).isEqualTo(DemandStatus.CANCELLED); assertThat(persisted.getCreatedBy().getId()).isEqualTo(user.getId());
    }

    @Test void databaseRejectsInvalidForeignKeys() {
        Material material = material("MAT-DEMAND-FK"); User user = user("demand-fk-user");
        assertMysqlConstraintViolation(() -> insert(99999999L, BigDecimal.ONE, user.getId()), "23000", 1452, "fk_demands_material");
        assertMysqlConstraintViolation(() -> insert(material.getId(), BigDecimal.ONE, 99999999L), "23000", 1452, "fk_demands_created_by");
    }

    @Test void databaseEnforcesPositiveQuantityForZeroAndNegativeValues() {
        Material material = material("MAT-DEMAND-CHECK"); User user = user("demand-check-user");
        assertMysqlConstraintViolation(() -> insert(material.getId(), BigDecimal.ZERO, user.getId()), "HY000", 3819, "chk_demands_quantity_positive");
        assertMysqlConstraintViolation(() -> insert(material.getId(), new BigDecimal("-1"), user.getId()), "HY000", 3819, "chk_demands_quantity_positive");
    }

    private void assertMysqlConstraintViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String sqlState, int errorCode, String constraint) {
        Throwable throwable = catchThrowable(action);
        assertThat(throwable).isNotNull();
        SQLException sqlException = findSqlException(throwable);
        assertNotNull(sqlException);
        assertThat(sqlException.getSQLState()).isEqualTo(sqlState);
        assertThat(sqlException.getErrorCode()).isEqualTo(errorCode);
        assertThat(sqlException.getMessage()).contains(constraint);
    }

    private SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) return sqlException;
            current = current.getCause();
        }
        return null;
    }

    private void insert(Long materialId, BigDecimal quantity, Long userId) { jdbcTemplate.update("INSERT INTO demands (material_id, quantity, required_date, status, created_by, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)", materialId, quantity, LocalDate.of(2026, 10, 8), userId, TestDataConstants.TEST_INSTANT, TestDataConstants.TEST_INSTANT); }
    private Material material(String code) { return materialRepository.saveAndFlush(Material.create(code, "Tea", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT)); }
    private User user(String username) { return userRepository.saveAndFlush(User.create(username, "hash", "Planner", UserRole.PRODUCTION_PLANNER, TestDataConstants.TEST_INSTANT)); }
}

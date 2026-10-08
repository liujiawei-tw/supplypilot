package com.supplypilot.supplier.repository;

import java.math.BigDecimal;

import com.supplypilot.integration.AbstractIntegrationTest;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.testsupport.TestDataConstants;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierRepositoryTests extends AbstractIntegrationTest {
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void persistsUpdatesAndLifecycleWithoutDeletingInactiveSupplier() {
        Supplier supplier = supplierRepository.saveAndFlush(Supplier.create(
                "SUP-REPO-LIFE", "Original", "TW", BigDecimal.ZERO, new BigDecimal("100"),
                TestDataConstants.TEST_INSTANT));
        supplier.update("Updated", null, new BigDecimal("25"), null, SupplierStatus.INACTIVE,
                TestDataConstants.TEST_INSTANT.plusSeconds(60));
        supplierRepository.saveAndFlush(supplier);

        Supplier reloaded = supplierRepository.findById(supplier.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Updated");
        assertThat(reloaded.getCountry()).isEqualTo("TW");
        assertThat(reloaded.getReliabilityScore()).isEqualByComparingTo("25");
        assertThat(reloaded.getQualityScore()).isEqualByComparingTo("100");
        assertThat(reloaded.getStatus()).isEqualTo(SupplierStatus.INACTIVE);
        assertThat(supplierRepository.existsById(supplier.getId())).isTrue();
    }

    @Test
    void enforcesSupplierCodeUniqueConstraint() {
        supplierRepository.saveAndFlush(Supplier.create(
                "SUP-REPO-UNIQUE", "One", null, BigDecimal.ZERO, BigDecimal.ZERO,
                TestDataConstants.TEST_INSTANT));
        assertThatThrownBy(() -> supplierRepository.saveAndFlush(Supplier.create(
                "SUP-REPO-UNIQUE", "Two", null, BigDecimal.ZERO, BigDecimal.ZERO,
                TestDataConstants.TEST_INSTANT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsScoreBoundariesInMySql() {
        int rows = insertRaw("SUP-REPO-BOUNDARY", BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void enforcesReliabilityScoreCheckConstraintInMySql() {
        assertThatThrownBy(() -> insertRaw("SUP-REPO-BAD-REL", new BigDecimal("-0.01"), BigDecimal.ZERO))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_suppliers_reliability_score_range");
    }

    @Test
    void enforcesQualityScoreCheckConstraintInMySql() {
        assertThatThrownBy(() -> insertRaw("SUP-REPO-BAD-QUAL", BigDecimal.ZERO, new BigDecimal("100.01")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_suppliers_quality_score_range");
    }

    private int insertRaw(String code, BigDecimal reliability, BigDecimal quality) {
        return jdbcTemplate.update("""
                INSERT INTO suppliers
                    (code, name, reliability_score, quality_score, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, code, "Raw Supplier", reliability, quality, "ACTIVE",
                TestDataConstants.TEST_INSTANT, TestDataConstants.TEST_INSTANT);
    }
}

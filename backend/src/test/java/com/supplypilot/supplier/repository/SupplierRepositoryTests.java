package com.supplypilot.supplier.repository;

import java.math.BigDecimal;
import com.supplypilot.integration.AbstractIntegrationTest;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.testsupport.TestDataConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierRepositoryTests extends AbstractIntegrationTest {
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Test void enforcesCodeUniquenessAndScoreChecks() {
        supplierRepository.saveAndFlush(Supplier.create("SUP-REPO-UNIQUE", "One", null, BigDecimal.ZERO, new BigDecimal("100"), TestDataConstants.TEST_INSTANT));
        assertThatThrownBy(() -> supplierRepository.saveAndFlush(Supplier.create("SUP-REPO-UNIQUE", "Two", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO suppliers (code, name, reliability_score, quality_score, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "SUP-REPO-INVALID", "Invalid", -1, 101, "ACTIVE", TestDataConstants.TEST_INSTANT, TestDataConstants.TEST_INSTANT)).isInstanceOf(DataIntegrityViolationException.class);
    }
}

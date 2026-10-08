package com.supplypilot.material.repository;

import java.math.BigDecimal;

import com.supplypilot.integration.AbstractIntegrationTest;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.testsupport.TestDataConstants;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterialRepositoryTests extends AbstractIntegrationTest {
    @Autowired private MaterialRepository materialRepository;

    @Test
    void enforcesMaterialCodeUniqueConstraint() {
        materialRepository.saveAndFlush(Material.create("MAT-REPO-UNIQUE", "One", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT));
        assertThatThrownBy(() -> materialRepository.saveAndFlush(
                Material.create("MAT-REPO-UNIQUE", "Two", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

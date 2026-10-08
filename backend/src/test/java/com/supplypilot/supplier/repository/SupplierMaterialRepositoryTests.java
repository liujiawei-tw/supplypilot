package com.supplypilot.supplier.repository;

import com.supplypilot.integration.AbstractIntegrationTest;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.supplier.domain.*;
import com.supplypilot.testsupport.TestDataConstants;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class SupplierMaterialRepositoryTests extends AbstractIntegrationTest {
    @Autowired SupplierMaterialRepository repository; @Autowired SupplierRepository supplierRepository;
    @Autowired MaterialRepository materialRepository; @Autowired JdbcTemplate jdbcTemplate;

    @Test void enforcesCompositeUniqueAndForeignKeys() {
        Supplier supplier = supplier(); Material material = material();
        repository.saveAndFlush(SupplierMaterial.create(supplier, material, BigDecimal.ZERO, BigDecimal.ONE, null, 0, Currency.TWD, TestDataConstants.TEST_INSTANT));
        assertThatThrownBy(() -> repository.saveAndFlush(SupplierMaterial.create(supplier, material, BigDecimal.ONE, BigDecimal.ONE, null, 0, Currency.TWD, TestDataConstants.TEST_INSTANT))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(supplier.getId() + 999999, material.getId(), "0", "1", null, 0, "TWD")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(supplier.getId(), material.getId() + 999999, "0", "1", null, 0, "TWD")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void enforcesDatabaseChecks() {
        Supplier supplier = supplier(); Material material = material();
        assertThatThrownBy(() -> insert(supplier.getId(), material.getId(), "-0.0001", "1", null, 0, "TWD")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert(supplier.getId(), material.getId(), "0", "0", null, 0, "TWD")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert(supplier.getId(), material.getId(), "0", "1", "0", 0, "TWD")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert(supplier.getId(), material.getId(), "0", "1", null, -1, "TWD")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert(supplier.getId(), material.getId(), "0", "1", null, 0, "USD")).isInstanceOf(DataAccessException.class);
    }

    @Test void persistsNullablePackageMultipleAndLifecycleWithoutDelete() {
        SupplierMaterial saved = repository.saveAndFlush(SupplierMaterial.create(supplier(), material(), BigDecimal.ZERO, BigDecimal.ONE, null, 0, Currency.TWD, TestDataConstants.TEST_INSTANT));
        Long id = saved.getId(); repository.flush();
        assertThat(repository.findById(id)).get().satisfies(value -> assertThat(value.getPackageMultiple()).isNull());
        saved.update(null, null, null, null, SupplierMaterialStatus.INACTIVE, TestDataConstants.TEST_INSTANT.plusSeconds(1)); repository.saveAndFlush(saved);
        saved.update(null, null, null, null, SupplierMaterialStatus.ACTIVE, TestDataConstants.TEST_INSTANT.plusSeconds(2)); repository.saveAndFlush(saved);
        assertThat(repository.findById(id)).get().extracting(SupplierMaterial::getStatus).isEqualTo(SupplierMaterialStatus.ACTIVE);
        assertThat(repository.existsById(id)).isTrue();
    }

    private Supplier supplier() { return supplierRepository.saveAndFlush(Supplier.create("SUP-REPO-" + System.nanoTime(), "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT)); }
    private Material material() { return materialRepository.saveAndFlush(Material.create("MAT-REPO-" + System.nanoTime(), "Material", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT)); }
    private void insert(long supplierId, long materialId, String unitPrice, String moq, String packageMultiple, int leadTimeDays, String currency) { jdbcTemplate.update("INSERT INTO supplier_materials (supplier_id, material_id, unit_price, moq, package_multiple, lead_time_days, currency, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", supplierId, materialId, new BigDecimal(unitPrice), new BigDecimal(moq), packageMultiple == null ? null : new BigDecimal(packageMultiple), leadTimeDays, currency); }
}

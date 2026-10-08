package com.supplypilot.supplier.service;

import com.supplypilot.common.exception.*;
import com.supplypilot.material.domain.*;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.supplier.domain.*;
import com.supplypilot.supplier.dto.request.*;
import com.supplypilot.supplier.mapper.SupplierMaterialMapper;
import com.supplypilot.supplier.repository.*;
import com.supplypilot.testsupport.TestDataConstants;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierMaterialServiceTests {
    @Mock SupplierMaterialRepository supplierMaterialRepository; @Mock SupplierRepository supplierRepository; @Mock MaterialRepository materialRepository;
    SupplierMaterialService service; Supplier supplier; Material material;
    @BeforeEach void setUp() { service = new SupplierMaterialService(supplierMaterialRepository, supplierRepository, materialRepository, new SupplierMaterialMapper(), Clock.fixed(TestDataConstants.TEST_INSTANT, ZoneOffset.UTC)); supplier = Supplier.create("SUP", "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT); material = Material.create("MAT", "Material", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT); ReflectionTestUtils.setField(supplier, "id", 1L); ReflectionTestUtils.setField(material, "id", 2L); }
    @Test void createsOnlyForActiveParentsAndUniquePair() {
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier)); when(materialRepository.findById(2L)).thenReturn(Optional.of(material)); when(supplierMaterialRepository.existsBySupplierIdAndMaterialId(1L, 2L)).thenReturn(false); when(supplierMaterialRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.createSupplierMaterial(request()).status()).isEqualTo(SupplierMaterialStatus.ACTIVE);
        verify(supplierMaterialRepository).existsBySupplierIdAndMaterialId(1L, 2L);
        when(supplierMaterialRepository.existsBySupplierIdAndMaterialId(1L, 2L)).thenReturn(true);
        assertThatThrownBy(() -> service.createSupplierMaterial(request())).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_SUPPLIER_MATERIAL);
    }
    @Test void distinguishesMissingInactiveAndUniqueConstraintFailures() {
        when(supplierRepository.findById(1L)).thenReturn(Optional.empty()); assertThatThrownBy(() -> service.createSupplierMaterial(request())).extracting("errorCode").isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
        supplier.update(null, null, null, null, SupplierStatus.INACTIVE, TestDataConstants.TEST_INSTANT); when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier)); assertThatThrownBy(() -> service.createSupplierMaterial(request())).extracting("errorCode").isEqualTo(ErrorCode.SUPPLIER_INACTIVE);
        Supplier active = Supplier.create("SUP2", "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT); ReflectionTestUtils.setField(active, "id", 1L); when(supplierRepository.findById(1L)).thenReturn(Optional.of(active)); when(materialRepository.findById(2L)).thenReturn(Optional.empty()); assertThatThrownBy(() -> service.createSupplierMaterial(request())).extracting("errorCode").isEqualTo(ErrorCode.MATERIAL_NOT_FOUND);
        material.update(null, null, MaterialStatus.INACTIVE, TestDataConstants.TEST_INSTANT); when(materialRepository.findById(2L)).thenReturn(Optional.of(material)); assertThatThrownBy(() -> service.createSupplierMaterial(request())).extracting("errorCode").isEqualTo(ErrorCode.MATERIAL_INACTIVE);
        material = Material.create("MAT2", "Material", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT); ReflectionTestUtils.setField(material, "id", 2L); when(materialRepository.findById(2L)).thenReturn(Optional.of(material)); when(supplierMaterialRepository.existsBySupplierIdAndMaterialId(1L, 2L)).thenReturn(false);
        when(supplierMaterialRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate", new ConstraintViolationException("duplicate", new java.sql.SQLException(), "uk_supplier_materials_supplier_material")));
        assertThatThrownBy(() -> service.createSupplierMaterial(request())).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_SUPPLIER_MATERIAL);
        reset(supplierMaterialRepository);
        when(supplierMaterialRepository.existsBySupplierIdAndMaterialId(1L, 2L)).thenReturn(false);
        when(supplierMaterialRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("check", new ConstraintViolationException("check", new java.sql.SQLException(), "chk_supplier_materials_moq")));
        assertThatThrownBy(() -> service.createSupplierMaterial(request())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void updatesProvidedValuesAndBlocksReactivationWhenParentIsInactive() {
        SupplierMaterial value = SupplierMaterial.create(supplier, material, BigDecimal.ZERO, BigDecimal.ONE, null, 0, Currency.TWD, TestDataConstants.TEST_INSTANT);
        ReflectionTestUtils.setField(value, "id", 10L); value.update(null, null, null, null, SupplierMaterialStatus.INACTIVE, TestDataConstants.TEST_INSTANT);
        when(supplierMaterialRepository.findById(10L)).thenReturn(Optional.of(value)); when(supplierMaterialRepository.save(value)).thenReturn(value);
        assertThat(service.updateSupplierMaterial(10L, new UpdateSupplierMaterialRequest(new BigDecimal("8"), null, null, null, null)).unitPrice()).isEqualByComparingTo("8");
        supplier.update(null, null, null, null, SupplierStatus.INACTIVE, TestDataConstants.TEST_INSTANT);
        assertThatThrownBy(() -> service.updateSupplierMaterial(10L, new UpdateSupplierMaterialRequest(null, null, null, null, SupplierMaterialStatus.ACTIVE))).extracting("errorCode").isEqualTo(ErrorCode.SUPPLIER_INACTIVE);
        supplier.update(null, null, null, null, SupplierStatus.ACTIVE, TestDataConstants.TEST_INSTANT);
        material.update(null, null, MaterialStatus.INACTIVE, TestDataConstants.TEST_INSTANT);
        assertThatThrownBy(() -> service.updateSupplierMaterial(10L, new UpdateSupplierMaterialRequest(null, null, null, null, SupplierMaterialStatus.ACTIVE))).extracting("errorCode").isEqualTo(ErrorCode.MATERIAL_INACTIVE);
        verify(supplierMaterialRepository, never()).delete(any(SupplierMaterial.class));
    }
    private CreateSupplierMaterialRequest request() { return new CreateSupplierMaterialRequest(1L, 2L, BigDecimal.ZERO, BigDecimal.ONE, null, 0, Currency.TWD); }
}

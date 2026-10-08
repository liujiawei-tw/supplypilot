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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierMaterialServiceTests {
    @Mock SupplierMaterialRepository supplierMaterialRepository; @Mock SupplierRepository supplierRepository; @Mock MaterialRepository materialRepository;
    SupplierMaterialService service; Supplier supplier; Material material;
    @BeforeEach void setUp() { service = new SupplierMaterialService(supplierMaterialRepository, supplierRepository, materialRepository, new SupplierMaterialMapper(), Clock.fixed(TestDataConstants.TEST_INSTANT, ZoneOffset.UTC)); supplier = Supplier.create("SUP", "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT); material = Material.create("MAT", "Material", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT); }
    @Test void createsOnlyForActiveParentsAndUniquePair() {
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier)); when(materialRepository.findById(2L)).thenReturn(Optional.of(material)); when(supplierMaterialRepository.existsBySupplierIdAndMaterialId(any(), any())).thenReturn(false); when(supplierMaterialRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.createSupplierMaterial(request()).status()).isEqualTo(SupplierMaterialStatus.ACTIVE);
        when(supplierMaterialRepository.existsBySupplierIdAndMaterialId(any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.createSupplierMaterial(request())).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_SUPPLIER_MATERIAL);
    }
    @Test void distinguishesMissingInactiveAndUniqueConstraintFailures() {
        when(supplierRepository.findById(1L)).thenReturn(Optional.empty()); assertThatThrownBy(() -> service.createSupplierMaterial(request())).extracting("errorCode").isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
        supplier.update(null, null, null, null, SupplierStatus.INACTIVE, TestDataConstants.TEST_INSTANT); when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier)); assertThatThrownBy(() -> service.createSupplierMaterial(request())).extracting("errorCode").isEqualTo(ErrorCode.SUPPLIER_INACTIVE);
        Supplier active = Supplier.create("SUP2", "Supplier", null, BigDecimal.ZERO, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT); when(supplierRepository.findById(1L)).thenReturn(Optional.of(active)); when(materialRepository.findById(2L)).thenReturn(Optional.of(material)); when(supplierMaterialRepository.existsBySupplierIdAndMaterialId(any(), any())).thenReturn(false);
        when(supplierMaterialRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate", new ConstraintViolationException("duplicate", new java.sql.SQLException(), "uk_supplier_materials_supplier_material")));
        assertThatThrownBy(() -> service.createSupplierMaterial(request())).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_SUPPLIER_MATERIAL);
        reset(supplierMaterialRepository);
        when(supplierMaterialRepository.existsBySupplierIdAndMaterialId(any(), any())).thenReturn(false);
        when(supplierMaterialRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("check", new ConstraintViolationException("check", new java.sql.SQLException(), "chk_supplier_materials_moq")));
        assertThatThrownBy(() -> service.createSupplierMaterial(request())).isInstanceOf(DataIntegrityViolationException.class);
    }
    private CreateSupplierMaterialRequest request() { return new CreateSupplierMaterialRequest(1L, 2L, BigDecimal.ZERO, BigDecimal.ONE, null, 0, Currency.TWD); }
}

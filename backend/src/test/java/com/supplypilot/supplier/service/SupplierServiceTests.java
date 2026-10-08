package com.supplypilot.supplier.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.dto.request.CreateSupplierRequest;
import com.supplypilot.supplier.mapper.SupplierMapper;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.testsupport.TestDataConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTests {
    @Mock private SupplierRepository supplierRepository;
    private SupplierService supplierService;
    @BeforeEach void setUp() { supplierService = new SupplierService(supplierRepository, new SupplierMapper(), Clock.fixed(TestDataConstants.TEST_INSTANT, ZoneOffset.UTC)); }
    @Test void createsSupplierAndRejectsDuplicateOrMissingSupplier() {
        when(supplierRepository.existsByCode("SUP-001")).thenReturn(false);
        when(supplierRepository.saveAndFlush(any(Supplier.class))).thenAnswer(invocation -> invocation.getArgument(0));
        supplierService.createSupplier(new CreateSupplierRequest("SUP-001", "Foods", "TW", new BigDecimal("92"), new BigDecimal("95")));
        verify(supplierRepository).saveAndFlush(any(Supplier.class));
        when(supplierRepository.existsByCode("SUP-002")).thenReturn(true);
        assertThatThrownBy(() -> supplierService.createSupplier(new CreateSupplierRequest("SUP-002", "Foods", null, BigDecimal.ZERO, BigDecimal.ZERO))).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_CODE);
        verify(supplierRepository, never()).findById(99L);
        when(supplierRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> supplierService.getSupplierById(99L)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
    }
    @Test void translatesOnlySupplierCodeConstraintViolation() {
        when(supplierRepository.existsByCode("SUP-001")).thenReturn(false);
        org.hibernate.exception.ConstraintViolationException violation = new org.hibernate.exception.ConstraintViolationException("duplicate", new java.sql.SQLException("duplicate"), "uk_suppliers_code");
        when(supplierRepository.saveAndFlush(any(Supplier.class))).thenThrow(new DataIntegrityViolationException("failure", violation));
        assertThatThrownBy(() -> supplierService.createSupplier(new CreateSupplierRequest("SUP-001", "Foods", null, BigDecimal.ZERO, BigDecimal.ZERO))).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_CODE);
        when(supplierRepository.saveAndFlush(any(Supplier.class))).thenThrow(new DataIntegrityViolationException("not null"));
        assertThatThrownBy(() -> supplierService.createSupplier(new CreateSupplierRequest("SUP-001", "Foods", null, BigDecimal.ZERO, BigDecimal.ZERO))).isInstanceOf(DataIntegrityViolationException.class);
    }
}

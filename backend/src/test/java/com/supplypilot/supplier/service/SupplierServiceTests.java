package com.supplypilot.supplier.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.supplier.dto.request.CreateSupplierRequest;
import com.supplypilot.supplier.dto.request.UpdateSupplierRequest;
import com.supplypilot.supplier.dto.response.SupplierResponse;
import com.supplypilot.supplier.mapper.SupplierMapper;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.testsupport.TestDataConstants;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTests {
    @Mock private SupplierRepository supplierRepository;
    private SupplierService supplierService;

    @BeforeEach
    void setUp() {
        supplierService = new SupplierService(
                supplierRepository,
                new SupplierMapper(),
                Clock.fixed(TestDataConstants.TEST_INSTANT, ZoneOffset.UTC)
        );
    }

    @Test
    void createsSupplierWithNormalizedCodeAndBoundaryScores() {
        when(supplierRepository.existsByCode("SUP-001")).thenReturn(false);
        when(supplierRepository.saveAndFlush(any(Supplier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierResponse response = supplierService.createSupplier(new CreateSupplierRequest(
                " SUP-001 ", "Foods", "TW", BigDecimal.ZERO, new BigDecimal("100")));

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("SUP-001");
        assertThat(response.status()).isEqualTo(SupplierStatus.ACTIVE);
        assertThat(response.reliabilityScore()).isEqualByComparingTo("0");
        assertThat(response.qualityScore()).isEqualByComparingTo("100");
    }

    @Test
    void rejectsDuplicateBeforeInsertAndReturnsSupplierNotFound() {
        when(supplierRepository.existsByCode("SUP-002")).thenReturn(true);
        assertThatThrownBy(() -> supplierService.createSupplier(new CreateSupplierRequest(
                "SUP-002", "Foods", null, BigDecimal.ZERO, BigDecimal.ZERO)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_CODE);
        verify(supplierRepository, never()).saveAndFlush(any());

        when(supplierRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> supplierService.getSupplierById(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
    }

    @Test
    void getsAndListsSuppliersUsingRequestedPage() {
        Supplier supplier = supplier("SUP-GET", "Foods", "TW", "80", "90");
        when(supplierRepository.findById(10L)).thenReturn(Optional.of(supplier));
        SupplierResponse found = supplierService.getSupplierById(10L);
        assertThat(found.code()).isEqualTo("SUP-GET");

        PageRequest pageable = PageRequest.of(1, 2);
        when(supplierRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(supplier), pageable, 3));
        PageResponse<SupplierResponse> result = supplierService.listSuppliers(
                SupplierStatus.ACTIVE, " GET ", " Foods ", pageable);

        assertThat(result.content()).singleElement().extracting(SupplierResponse::code).isEqualTo("SUP-GET");
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(3);
        verify(supplierRepository).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void updatesOnlyProvidedFieldsAndSupportsLifecycleWithoutDeletingSupplier() {
        Supplier supplier = supplier("SUP-UPD", "Original", "TW", "50", "60");
        when(supplierRepository.findById(20L)).thenReturn(Optional.of(supplier));
        when(supplierRepository.save(supplier)).thenReturn(supplier);

        SupplierResponse inactive = supplierService.updateSupplier(20L, new UpdateSupplierRequest(
                "Updated", null, BigDecimal.ZERO, null, SupplierStatus.INACTIVE));
        assertThat(inactive.name()).isEqualTo("Updated");
        assertThat(inactive.country()).isEqualTo("TW");
        assertThat(inactive.reliabilityScore()).isEqualByComparingTo("0");
        assertThat(inactive.qualityScore()).isEqualByComparingTo("60");
        assertThat(inactive.status()).isEqualTo(SupplierStatus.INACTIVE);

        SupplierResponse active = supplierService.updateSupplier(20L, new UpdateSupplierRequest(
                null, "", null, new BigDecimal("100"), SupplierStatus.ACTIVE));
        assertThat(active.country()).isNull();
        assertThat(active.qualityScore()).isEqualByComparingTo("100");
        assertThat(active.status()).isEqualTo(SupplierStatus.ACTIVE);
        verify(supplierRepository, never()).delete(any(Supplier.class));
        verify(supplierRepository, never()).deleteById(any());
    }

    @Test
    void translatesOnlySupplierCodeConstraintViolation() {
        when(supplierRepository.existsByCode("SUP-001")).thenReturn(false);
        ConstraintViolationException uniqueViolation = new ConstraintViolationException(
                "duplicate", new java.sql.SQLException("duplicate"), "uk_suppliers_code");
        when(supplierRepository.saveAndFlush(any(Supplier.class)))
                .thenThrow(new DataIntegrityViolationException("failure", uniqueViolation));
        assertThatThrownBy(() -> supplierService.createSupplier(request("SUP-001")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_CODE);

        ConstraintViolationException unrelatedViolation = new ConstraintViolationException(
                "check", new java.sql.SQLException("check"), "chk_suppliers_quality_score_range");
        when(supplierRepository.saveAndFlush(any(Supplier.class)))
                .thenThrow(new DataIntegrityViolationException("failure", unrelatedViolation));
        assertThatThrownBy(() -> supplierService.createSupplier(request("SUP-001")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CreateSupplierRequest request(String code) {
        return new CreateSupplierRequest(code, "Foods", null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private Supplier supplier(String code, String name, String country, String reliability, String quality) {
        return Supplier.create(code, name, country, new BigDecimal(reliability), new BigDecimal(quality),
                TestDataConstants.TEST_INSTANT);
    }
}

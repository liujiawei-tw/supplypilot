package com.supplypilot.supplier.service;

import java.time.Clock;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierService {
    private final SupplierRepository supplierRepository; private final SupplierMapper supplierMapper; private final Clock clock;
    public SupplierService(SupplierRepository supplierRepository, SupplierMapper supplierMapper, Clock clock) {
        this.supplierRepository=supplierRepository; this.supplierMapper=supplierMapper; this.clock=clock;
    }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse createSupplier(CreateSupplierRequest request) {
        String code = request.code().trim();
        if (supplierRepository.existsByCode(code)) throw new BusinessException(ErrorCode.DUPLICATE_CODE);
        try { return supplierMapper.toResponse(supplierRepository.saveAndFlush(Supplier.create(code, request.name(), request.country(), request.reliabilityScore(), request.qualityScore(), clock.instant()))); }
        catch (DataIntegrityViolationException exception) {
            if (isSupplierCodeUniqueConstraintViolation(exception)) throw new BusinessException(ErrorCode.DUPLICATE_CODE);
            throw exception;
        }
    }
    @Transactional(readOnly = true) @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF','PROCUREMENT_MANAGER','ADMIN')")
    public PageResponse<SupplierResponse> listSuppliers(SupplierStatus status, String code, String name, Pageable pageable) {
        Specification<Supplier> specification = Specification.unrestricted();
        if (status != null) {
            specification = specification.and(
                    (root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (hasText(code)) {
            specification = specification.and(
                    (root, query, builder) -> builder.like(root.get("code"), "%" + code.trim() + "%"));
        }
        if (hasText(name)) {
            specification = specification.and(
                    (root, query, builder) -> builder.like(root.get("name"), "%" + name.trim() + "%"));
        }
        Page<Supplier> page = supplierRepository.findAll(specification, pageable);
        return PageResponse.from(page.map(supplierMapper::toResponse));
    }
    @Transactional(readOnly = true) @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF','PROCUREMENT_MANAGER','ADMIN')")
    public SupplierResponse getSupplierById(Long id) { return supplierMapper.toResponse(findSupplier(id)); }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse updateSupplier(Long id, UpdateSupplierRequest request) {
        Supplier supplier = findSupplier(id);
        supplier.update(request.name(), request.country(), request.reliabilityScore(), request.qualityScore(), request.status(), clock.instant());
        return supplierMapper.toResponse(supplierRepository.save(supplier));
    }
    private Supplier findSupplier(Long id) { return supplierRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND)); }
    private boolean isSupplierCodeUniqueConstraintViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception.getCause();
        return cause instanceof org.hibernate.exception.ConstraintViolationException violation && "uk_suppliers_code".equalsIgnoreCase(violation.getConstraintName());
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}

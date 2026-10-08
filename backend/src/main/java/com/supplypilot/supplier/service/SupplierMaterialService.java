package com.supplypilot.supplier.service;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.supplier.domain.*;
import com.supplypilot.supplier.dto.request.CreateSupplierMaterialRequest;
import com.supplypilot.supplier.dto.request.UpdateSupplierMaterialRequest;
import com.supplypilot.supplier.dto.response.SupplierMaterialResponse;
import com.supplypilot.supplier.mapper.SupplierMaterialMapper;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import java.time.Clock;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierMaterialService {
    private final SupplierMaterialRepository supplierMaterialRepository; private final SupplierRepository supplierRepository;
    private final MaterialRepository materialRepository; private final SupplierMaterialMapper mapper; private final Clock clock;
    public SupplierMaterialService(SupplierMaterialRepository supplierMaterialRepository, SupplierRepository supplierRepository, MaterialRepository materialRepository, SupplierMaterialMapper mapper, Clock clock) {
        this.supplierMaterialRepository = supplierMaterialRepository; this.supplierRepository = supplierRepository; this.materialRepository = materialRepository; this.mapper = mapper; this.clock = clock;
    }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public SupplierMaterialResponse createSupplierMaterial(CreateSupplierMaterialRequest request) {
        Supplier supplier = findSupplier(request.supplierId()); validateActive(supplier);
        Material material = findMaterial(request.materialId()); validateActive(material);
        if (supplierMaterialRepository.existsBySupplierIdAndMaterialId(supplier.getId(), material.getId())) throw new BusinessException(ErrorCode.DUPLICATE_SUPPLIER_MATERIAL);
        try { return mapper.toResponse(supplierMaterialRepository.saveAndFlush(SupplierMaterial.create(supplier, material, request.unitPrice(), request.moq(), request.packageMultiple(), request.leadTimeDays(), request.currency(), clock.instant()))); }
        catch (DataIntegrityViolationException exception) { if (isPairUniqueViolation(exception)) throw new BusinessException(ErrorCode.DUPLICATE_SUPPLIER_MATERIAL); throw exception; }
    }
    @Transactional(readOnly = true) @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF','PROCUREMENT_MANAGER','ADMIN')")
    public PageResponse<SupplierMaterialResponse> listSupplierMaterials(Long supplierId, Long materialId, SupplierMaterialStatus status, Pageable pageable) {
        Specification<SupplierMaterial> spec = Specification.allOf(supplierId == null ? null : (root, query, builder) -> builder.equal(root.get("supplier").get("id"), supplierId), materialId == null ? null : (root, query, builder) -> builder.equal(root.get("material").get("id"), materialId), status == null ? null : (root, query, builder) -> builder.equal(root.get("status"), status));
        Page<SupplierMaterial> page = supplierMaterialRepository.findAll(spec, pageable); return PageResponse.from(page.map(mapper::toResponse));
    }
    @Transactional(readOnly = true) @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF','PROCUREMENT_MANAGER','ADMIN')")
    public SupplierMaterialResponse getSupplierMaterialById(Long id) { return mapper.toResponse(findSupplierMaterial(id)); }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public SupplierMaterialResponse updateSupplierMaterial(Long id, UpdateSupplierMaterialRequest request) {
        SupplierMaterial value = findSupplierMaterial(id);
        if (request.status() == SupplierMaterialStatus.ACTIVE && value.getStatus() != SupplierMaterialStatus.ACTIVE) { validateActive(value.getSupplier()); validateActive(value.getMaterial()); }
        value.update(request.unitPrice(), request.moq(), request.packageMultiple(), request.leadTimeDays(), request.status(), clock.instant());
        return mapper.toResponse(supplierMaterialRepository.save(value));
    }
    private Supplier findSupplier(Long id) { return supplierRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND)); }
    private Material findMaterial(Long id) { return materialRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND)); }
    private SupplierMaterial findSupplierMaterial(Long id) { return supplierMaterialRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_MATERIAL_NOT_FOUND)); }
    private void validateActive(Supplier value) { if (value.getStatus() != SupplierStatus.ACTIVE) throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE); }
    private void validateActive(Material value) { if (value.getStatus() != MaterialStatus.ACTIVE) throw new BusinessException(ErrorCode.MATERIAL_INACTIVE); }
    private boolean isPairUniqueViolation(DataIntegrityViolationException exception) { Throwable cause = exception.getCause(); return cause instanceof ConstraintViolationException violation && "uk_supplier_materials_supplier_material".equalsIgnoreCase(violation.getConstraintName()); }
}

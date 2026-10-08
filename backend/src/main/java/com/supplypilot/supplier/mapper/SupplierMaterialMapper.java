package com.supplypilot.supplier.mapper;

import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.dto.response.SupplierMaterialResponse;
import org.springframework.stereotype.Component;

@Component
public class SupplierMaterialMapper {
    public SupplierMaterialResponse toResponse(SupplierMaterial value) {
        return new SupplierMaterialResponse(value.getId(), value.getSupplier().getId(), value.getMaterial().getId(), value.getUnitPrice(), value.getMoq(), value.getPackageMultiple(), value.getLeadTimeDays(), value.getCurrency(), value.getStatus(), value.getCreatedAt(), value.getUpdatedAt());
    }
}

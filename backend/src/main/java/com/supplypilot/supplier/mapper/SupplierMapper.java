package com.supplypilot.supplier.mapper;

import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.dto.response.SupplierResponse;
import org.springframework.stereotype.Component;

@Component
public class SupplierMapper {
    public SupplierResponse toResponse(Supplier supplier) {
        return new SupplierResponse(supplier.getId(), supplier.getCode(), supplier.getName(), supplier.getCountry(),
                supplier.getReliabilityScore(), supplier.getQualityScore(), supplier.getStatus(), supplier.getCreatedAt(), supplier.getUpdatedAt());
    }
}

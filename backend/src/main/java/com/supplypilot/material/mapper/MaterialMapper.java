package com.supplypilot.material.mapper;

import com.supplypilot.material.domain.Material;
import com.supplypilot.material.dto.response.MaterialResponse;

import org.springframework.stereotype.Component;

@Component
public class MaterialMapper {
    public MaterialResponse toResponse(Material material) {
        return new MaterialResponse(
                material.getId(), material.getCode(), material.getName(), material.getBaseUnit(),
                material.getSafetyStock(), material.getStatus(), material.getCreatedAt(), material.getUpdatedAt()
        );
    }
}

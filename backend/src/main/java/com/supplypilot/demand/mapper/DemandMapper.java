package com.supplypilot.demand.mapper;

import com.supplypilot.demand.domain.Demand;
import com.supplypilot.demand.dto.response.DemandResponse;
import org.springframework.stereotype.Component;

@Component
public class DemandMapper {
    public DemandResponse toResponse(Demand demand) {
        return new DemandResponse(demand.getId(), demand.getMaterial().getId(), demand.getQuantity(), demand.getRequiredDate(),
                demand.getStatus(), demand.getNote(), demand.getCreatedBy().getId(), demand.getCreatedAt(), demand.getUpdatedAt());
    }
}

package com.supplypilot.demand.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.demand.domain.Demand;
import com.supplypilot.demand.domain.DemandStatus;
import com.supplypilot.demand.dto.request.CreateDemandRequest;
import com.supplypilot.demand.dto.request.UpdateDemandRequest;
import com.supplypilot.demand.dto.response.DemandResponse;
import com.supplypilot.demand.mapper.DemandMapper;
import com.supplypilot.demand.repository.DemandRepository;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.repository.UserRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemandService {
    private final DemandRepository demandRepository;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final DemandMapper demandMapper;
    private final Clock clock;

    public DemandService(DemandRepository demandRepository, MaterialRepository materialRepository, UserRepository userRepository,
                         CurrentUserProvider currentUserProvider, DemandMapper demandMapper, Clock clock) {
        this.demandRepository = demandRepository;
        this.materialRepository = materialRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.demandMapper = demandMapper;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PRODUCTION_PLANNER', 'ADMIN')")
    public DemandResponse createDemand(CreateDemandRequest request) {
        LocalDate today = LocalDate.now(clock);
        if (request.requiredDate().isBefore(today)) throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        Material material = materialRepository.findById(request.materialId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
        if (material.getStatus() != MaterialStatus.ACTIVE) throw new BusinessException(ErrorCode.MATERIAL_INACTIVE);
        User createdBy = userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Demand demand = Demand.create(material, request.quantity(), request.requiredDate(), request.note(), createdBy, clock.instant());
        return demandMapper.toResponse(demandRepository.save(demand));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PRODUCTION_PLANNER', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public PageResponse<DemandResponse> listDemands(Long materialId, DemandStatus status, LocalDate requiredFrom,
                                                     LocalDate requiredTo, Pageable pageable) {
        validateRange(requiredFrom, requiredTo);
        Specification<Demand> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (materialId != null) predicates.add(builder.equal(root.get("material").get("id"), materialId));
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (requiredFrom != null) predicates.add(builder.greaterThanOrEqualTo(root.get("requiredDate"), requiredFrom));
            if (requiredTo != null) predicates.add(builder.lessThanOrEqualTo(root.get("requiredDate"), requiredTo));
            return predicates.isEmpty() ? builder.conjunction() : builder.and(predicates.toArray(Predicate[]::new));
        };
        Page<Demand> page = demandRepository.findAll(specification, pageable);
        return PageResponse.from(page.map(demandMapper::toResponse));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PRODUCTION_PLANNER', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public DemandResponse getDemandById(Long id) { return demandMapper.toResponse(findDemand(id)); }

    @Transactional
    @PreAuthorize("hasAnyRole('PRODUCTION_PLANNER', 'ADMIN')")
    public DemandResponse updateDemand(Long id, UpdateDemandRequest request) {
        Demand demand = findDemand(id);
        demand.update(request.quantity(), request.requiredDate(), request.note(), request.noteProvided(), LocalDate.now(clock), clock.instant());
        return demandMapper.toResponse(demandRepository.save(demand));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PRODUCTION_PLANNER', 'ADMIN')")
    public DemandResponse cancelDemand(Long id) {
        Demand demand = findDemand(id);
        demand.cancel(clock.instant());
        return demandMapper.toResponse(demandRepository.save(demand));
    }

    private Demand findDemand(Long id) { return demandRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.DEMAND_NOT_FOUND)); }
    private void validateRange(LocalDate requiredFrom, LocalDate requiredTo) {
        if (requiredFrom != null && requiredTo != null && requiredFrom.isAfter(requiredTo)) throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
    }
}

package com.supplypilot.demand.controller;

import java.time.LocalDate;
import java.util.Set;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.demand.domain.DemandStatus;
import com.supplypilot.demand.dto.request.CreateDemandRequest;
import com.supplypilot.demand.dto.request.UpdateDemandRequest;
import com.supplypilot.demand.dto.response.DemandResponse;
import com.supplypilot.demand.service.DemandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/demands")
@Validated
public class DemandController {
    private static final Set<String> SORTABLE_FIELDS = Set.of("id", "quantity", "requiredDate", "status", "createdAt", "updatedAt");
    private final DemandService demandService;
    public DemandController(DemandService demandService) { this.demandService = demandService; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public DemandResponse createDemand(@Valid @RequestBody CreateDemandRequest request) { return demandService.createDemand(request); }

    @GetMapping
    public PageResponse<DemandResponse> listDemands(@RequestParam(required = false) Long materialId,
            @RequestParam(required = false) DemandStatus status, @RequestParam(required = false) LocalDate requiredFrom,
            @RequestParam(required = false) LocalDate requiredTo, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, @RequestParam(defaultValue = "id,asc") String sort) {
        return demandService.listDemands(materialId, status, requiredFrom, requiredTo, PageRequest.of(page, size, parseSort(sort)));
    }
    @GetMapping("/{id}") public DemandResponse getDemandById(@PathVariable Long id) { return demandService.getDemandById(id); }
    @PatchMapping("/{id}") public DemandResponse updateDemand(@PathVariable Long id, @Valid @RequestBody UpdateDemandRequest request) { return demandService.updateDemand(id, request); }
    @PostMapping("/{id}/cancel") public DemandResponse cancelDemand(@PathVariable Long id) { return demandService.cancelDemand(id); }

    private Sort parseSort(String sort) {
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || parts[0].isBlank() || !SORTABLE_FIELDS.contains(parts[0]) ||
                (parts.length == 2 && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc")))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return Sort.by(parts.length == 2 && parts[1].equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC, parts[0]);
    }
}

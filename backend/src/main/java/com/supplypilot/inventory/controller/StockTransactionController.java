package com.supplypilot.inventory.controller;

import java.time.Instant;
import java.util.Set;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.inventory.domain.StockTransactionType;
import com.supplypilot.inventory.dto.response.StockTransactionReadResponse;
import com.supplypilot.inventory.service.StockTransactionQueryService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock-transactions")
@Validated
public class StockTransactionController {
    private static final Set<String> SORTABLE_FIELDS = Set.of("id", "createdAt", "materialId", "type", "purchaseOrderId", "quantity");
    private final StockTransactionQueryService service;

    public StockTransactionController(StockTransactionQueryService service) { this.service = service; }

    @GetMapping
    public PageResponse<StockTransactionReadResponse> list(
            @RequestParam(required = false) @Positive Long materialId,
            @RequestParam(required = false) StockTransactionType type,
            @RequestParam(required = false) @Positive Long purchaseOrderId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return service.list(materialId, type, purchaseOrderId, from, to, PageRequest.of(page, size, parseSort(sort)));
    }

    @GetMapping("/{id}")
    public StockTransactionReadResponse get(@PathVariable @Positive Long id) { return service.get(id); }

    private Sort parseSort(String sort) {
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || parts[0].isBlank() || !SORTABLE_FIELDS.contains(parts[0])
                || (parts.length == 2 && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc"))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String property = parts[0].equals("materialId") ? "material.id" : parts[0];
        Sort sortValue = Sort.by(parts.length == 2 && parts[1].equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, property);
        return parts[0].equals("id") ? sortValue : sortValue.and(Sort.by(Sort.Direction.ASC, "id"));
    }
}

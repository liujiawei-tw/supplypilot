package com.supplypilot.inventory.importing.controller;

import com.supplypilot.inventory.importing.dto.response.InventoryImportValidationResponse;
import com.supplypilot.inventory.importing.service.InventoryImportValidationService;
import com.supplypilot.inventory.importing.service.InventoryImportConfirmService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/inventory-imports")
public class InventoryImportController {

    private final InventoryImportValidationService validationService;
    private final InventoryImportConfirmService confirmService;

    public InventoryImportController(InventoryImportValidationService validationService, InventoryImportConfirmService confirmService) {
        this.validationService = validationService;
        this.confirmService = confirmService;
    }

    @PostMapping(path = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InventoryImportValidationResponse validate(@RequestPart("file") MultipartFile file) {
        return validationService.validate(file);
    }

    @PostMapping("/{batchId}/confirm")
    public void confirm(@PathVariable Long batchId) {
        confirmService.confirm(batchId);
    }
}

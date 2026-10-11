package com.supplypilot.document.controller;

import java.util.Set;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.dto.request.UpdateDocumentRequest;
import com.supplypilot.document.dto.response.DocumentResponse;
import com.supplypilot.document.service.DocumentService;
import com.supplypilot.document.rag.DocumentIngestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
@Validated
public class DocumentController {

    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "id", "documentType", "supplier", "title", "version", "status", "uploadedAt", "updatedAt");
    private final DocumentService documentService;
    private final DocumentIngestionService ingestionService;

    public DocumentController(DocumentService documentService, DocumentIngestionService ingestionService) {
        this.documentService = documentService;
        this.ingestionService = ingestionService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(
            @RequestParam @NotNull DocumentType documentType,
            @RequestParam(required = false) Long supplierId,
            @RequestParam @NotBlank @Size(max = 255) String title,
            @RequestParam @NotBlank @Size(max = 50) String version,
            @RequestPart("file") MultipartFile file
    ) {
        return documentService.upload(documentType, supplierId, title, version, file);
    }

    @GetMapping
    public PageResponse<DocumentResponse> list(
            @RequestParam(required = false) DocumentType documentType,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "uploadedAt,desc") String sort
    ) {
        return documentService.list(documentType, supplierId, status, PageRequest.of(page, size, parseSort(sort)));
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable Long id) {
        return documentService.get(id);
    }

    @PostMapping("/{id}/ingestions")
    public DocumentResponse ingest(@PathVariable Long id) {
        return ingestionService.ingest(id);
    }

    @PatchMapping("/{id}")
    public DocumentResponse update(@PathVariable Long id, @Valid @RequestBody UpdateDocumentRequest request) {
        return documentService.update(id, request);
    }

    private Sort parseSort(String sort) {
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || parts[0].isBlank() || !SORTABLE_FIELDS.contains(parts[0])
                || (parts.length == 2 && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc"))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Sort.Direction direction = parts.length == 2 && parts[1].equalsIgnoreCase("desc")
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        String property = parts[0].equals("supplier") ? "supplier.id" : parts[0];
        Sort requested = Sort.by(direction, property);
        return parts[0].equals("id") ? requested : requested.and(Sort.by(Sort.Direction.ASC, "id"));
    }
}

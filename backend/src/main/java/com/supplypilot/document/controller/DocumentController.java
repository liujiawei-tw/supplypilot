package com.supplypilot.document.controller;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.dto.request.UpdateDocumentRequest;
import com.supplypilot.document.dto.response.DocumentResponse;
import com.supplypilot.document.rag.DocumentIngestionService;
import com.supplypilot.document.service.DocumentFileContent;
import com.supplypilot.document.service.DocumentFileService;
import com.supplypilot.document.service.DocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final DocumentFileService documentFileService;

    public DocumentController(
            DocumentService documentService,
            DocumentIngestionService ingestionService,
            DocumentFileService documentFileService
    ) {
        this.documentService = documentService;
        this.ingestionService = ingestionService;
        this.documentFileService = documentFileService;
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

    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> preview(@PathVariable Long id) {
        return fileResponse(documentFileService.preview(id), false);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        return fileResponse(documentFileService.download(id), true);
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

    private ResponseEntity<Resource> fileResponse(DocumentFileContent file, boolean attachment) {
        ContentDisposition disposition = (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(file.mediaType())
                .contentLength(file.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(new BoundedDocumentResource(file));
    }

    private static final class BoundedDocumentResource extends AbstractResource {

        private final DocumentFileContent file;

        private BoundedDocumentResource(DocumentFileContent file) {
            this.file = file;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream source = file.content().getInputStream();
            return new InputStream() {
                private long remaining = file.contentLength();

                @Override
                public int read() throws IOException {
                    if (remaining == 0) {
                        if (source.read() >= 0) throw new IOException("Stored document changed while streaming");
                        return -1;
                    }
                    int value = source.read();
                    if (value < 0) throw new EOFException("Stored document ended before the expected size");
                    remaining--;
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    if (remaining == 0) {
                        if (source.read() >= 0) throw new IOException("Stored document changed while streaming");
                        return -1;
                    }
                    int read = source.read(buffer, offset, (int) Math.min(length, remaining));
                    if (read < 0) throw new EOFException("Stored document ended before the expected size");
                    remaining -= read;
                    return read;
                }

                @Override
                public void close() throws IOException {
                    source.close();
                }
            };
        }

        @Override
        public long contentLength() {
            return file.contentLength();
        }

        @Override
        public String getFilename() {
            return file.filename();
        }

        @Override
        public String getDescription() {
            return "Stored document content";
        }
    }
}

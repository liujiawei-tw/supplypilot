package com.supplypilot.document.service;

import java.time.Clock;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.dto.request.UpdateDocumentRequest;
import com.supplypilot.document.dto.response.DocumentResponse;
import com.supplypilot.document.mapper.DocumentMapper;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {

    private final DocumentMetadataRepository documentRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;
    private final DocumentStorageService storageService;
    private final DocumentFileValidator fileValidator;
    private final DocumentMapper documentMapper;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public DocumentService(
            DocumentMetadataRepository documentRepository,
            SupplierRepository supplierRepository,
            UserRepository userRepository,
            DocumentStorageService storageService,
            DocumentFileValidator fileValidator,
            DocumentMapper documentMapper,
            CurrentUserProvider currentUserProvider,
            Clock clock
    ) {
        this.documentRepository = documentRepository;
        this.supplierRepository = supplierRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.fileValidator = fileValidator;
        this.documentMapper = documentMapper;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'ADMIN')")
    public DocumentResponse upload(
            DocumentType documentType,
            Long supplierId,
            String title,
            String version,
            MultipartFile file
    ) {
        String extension = fileValidator.validate(file);
        Supplier supplier = supplierId == null ? null : supplierRepository.findById(supplierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND));
        User uploadedBy = userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String storagePath = storageService.store(file, extension);
        try {
            DocumentMetadata document = DocumentMetadata.processing(
                    documentType, supplier, title, version, storagePath, uploadedBy, clock.instant());
            return documentMapper.toResponse(documentRepository.saveAndFlush(document));
        } catch (RuntimeException persistenceFailure) {
            try {
                storageService.delete(storagePath);
            } catch (RuntimeException compensationFailure) {
                persistenceFailure.addSuppressed(compensationFailure);
            }
            throw persistenceFailure;
        }
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public PageResponse<DocumentResponse> list(
            DocumentType documentType,
            Long supplierId,
            DocumentStatus status,
            Pageable pageable
    ) {
        Specification<DocumentMetadata> specification = (root, query, builder) -> builder.conjunction();
        if (documentType != null) {
            specification = specification.and(
                    (root, query, builder) -> builder.equal(root.get("documentType"), documentType));
        }
        if (supplierId != null) {
            specification = specification.and(
                    (root, query, builder) -> builder.equal(root.get("supplier").get("id"), supplierId));
        }
        if (status != null) {
            specification = specification.and(
                    (root, query, builder) -> builder.equal(root.get("status"), status));
        }
        Page<DocumentMetadata> page = documentRepository.findAll(specification, pageable);
        return PageResponse.from(page.map(documentMapper::toResponse));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public DocumentResponse get(Long id) {
        return documentMapper.toResponse(findDocument(id));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'ADMIN')")
    public DocumentResponse update(Long id, UpdateDocumentRequest request) {
        DocumentMetadata document = findDocumentForUpdate(id);
        document.updateMetadata(request.title(), request.status(), clock.instant());
        return documentMapper.toResponse(documentRepository.save(document));
    }

    private DocumentMetadata findDocument(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    private DocumentMetadata findDocumentForUpdate(Long id) {
        return documentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }
}

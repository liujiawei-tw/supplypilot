package com.supplypilot.document.service;

import java.time.Clock;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

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
import com.supplypilot.document.semantic.DocumentSemanticLifecycleReader;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final DocumentSemanticLifecycleReader lifecycleReader;

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
        this(documentRepository, supplierRepository, userRepository, storageService, fileValidator,
                documentMapper, currentUserProvider, clock, null);
    }

    @Autowired
    public DocumentService(
            DocumentMetadataRepository documentRepository,
            SupplierRepository supplierRepository,
            UserRepository userRepository,
            DocumentStorageService storageService,
            DocumentFileValidator fileValidator,
            DocumentMapper documentMapper,
            CurrentUserProvider currentUserProvider,
            Clock clock,
            DocumentSemanticLifecycleReader lifecycleReader
    ) {
        this.documentRepository = documentRepository;
        this.supplierRepository = supplierRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.fileValidator = fileValidator;
        this.documentMapper = documentMapper;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
        this.lifecycleReader = lifecycleReader;
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
            return toResponse(documentRepository.saveAndFlush(document));
        } catch (RuntimeException persistenceFailure) {
            try {
                storageService.delete(storagePath);
            } catch (RuntimeException compensationFailure) {
                persistenceFailure.addSuppressed(compensationFailure);
            }
            throw persistenceFailure;
        }
    }

    /** Creates a server-owned trusted DEMO baseline without assigning a human uploader. */
    @Transactional
    public DocumentResponse createTrustedDemoBaseline(
            DocumentType documentType, Long supplierId, String title, String version, byte[] source,
            String datasetKey, String documentKey, String themeKey, String corpusVersion,
            String sourceSha256, String provenanceOrigin) {
        if (source == null || source.length == 0 || source.length > DocumentFileValidator.MAX_FILE_BYTES
                || !canonicalSha256(source).equalsIgnoreCase(sourceSha256)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Supplier supplier = supplierId == null ? null : supplierRepository.findById(supplierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND));
        MultipartFile file = new ByteArrayMultipartFile("corpus.txt", source);
        String extension = fileValidator.validate(file);
        String storagePath = storageService.store(file, extension);
        try {
            DocumentMetadata document = DocumentMetadata.demoBaselineProcessing(documentType, supplier,
                    title, version, storagePath, datasetKey, documentKey, themeKey, corpusVersion,
                    sourceSha256, provenanceOrigin, clock.instant());
            return toResponse(documentRepository.saveAndFlush(document));
        } catch (RuntimeException persistenceFailure) {
            try { storageService.delete(storagePath); }
            catch (RuntimeException compensationFailure) { persistenceFailure.addSuppressed(compensationFailure); }
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
        return PageResponse.from(page.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public DocumentResponse get(Long id) {
        return toResponse(findDocument(id));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'ADMIN')")
    public DocumentResponse update(Long id, UpdateDocumentRequest request) {
        DocumentMetadata document = findDocumentForUpdate(id);
        document.updateMetadata(request.title(), request.status(), clock.instant());
        return toResponse(documentRepository.save(document));
    }

    private DocumentResponse toResponse(DocumentMetadata document) {
        return documentMapper.toResponse(document,
                lifecycleReader == null ? null : lifecycleReader.read(document));
    }

    private DocumentMetadata findDocument(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    private DocumentMetadata findDocumentForUpdate(Long id) {
        return documentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    private static String canonicalSha256(byte[] source) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(source)).toString();
            byte[] canonical = text.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (java.nio.charset.CharacterCodingException | NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static final class ByteArrayMultipartFile implements MultipartFile {
        private final String filename;
        private final byte[] bytes;

        private ByteArrayMultipartFile(String filename, byte[] bytes) {
            this.filename = filename;
            this.bytes = bytes.clone();
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return "text/plain"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), bytes); }
    }
}

package com.supplypilot.document.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.rag.DocumentParsingException;
import com.supplypilot.document.rag.DocumentTextExtractor;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import org.springframework.core.io.InputStreamSource;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Provides bounded, ID-addressed access to stored document bytes and text previews. */
@Service
public class DocumentFileService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".txt", ".docx");
    private static final MediaType DOCX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final long MAX_PREVIEW_BYTES = DocumentFileValidator.MAX_FILE_BYTES;

    private final DocumentMetadataRepository documentRepository;
    private final DocumentStorageService storageService;
    private final DocumentTextExtractor textExtractor;

    public DocumentFileService(
            DocumentMetadataRepository documentRepository,
            DocumentStorageService storageService,
            DocumentTextExtractor textExtractor
    ) {
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.textExtractor = textExtractor;
    }

    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public DocumentFileContent preview(Long documentId) {
        DocumentMetadata document = findDocument(documentId);
        String storagePath = document.getStoragePath();
        String extension = extension(storagePath);
        long sourceSize = checkedSize(storagePath);
        String filename = safeFilename(document.getTitle(), documentId, extension);

        if (extension.equals(".pdf")) {
            return new DocumentFileContent(filename, MediaType.APPLICATION_PDF, sourceSize,
                    () -> storageService.read(storagePath));
        }

        try {
            byte[] text = textExtractor.extractPreviewText(storagePath).getBytes(StandardCharsets.UTF_8);
            if (text.length > MAX_PREVIEW_BYTES) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
            }
            return new DocumentFileContent(filename, new MediaType("text", "plain", StandardCharsets.UTF_8),
                    text.length, () -> new ByteArrayInputStream(text));
        } catch (DocumentParsingException exception) {
            throw new BusinessException(ErrorCode.DOCUMENT_PREVIEW_INVALID);
        }
    }

    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public DocumentFileContent download(Long documentId) {
        DocumentMetadata document = findDocument(documentId);
        String storagePath = document.getStoragePath();
        String extension = extension(storagePath);
        long sourceSize = checkedSize(storagePath);
        MediaType mediaType = switch (extension) {
            case ".pdf" -> MediaType.APPLICATION_PDF;
            case ".txt" -> MediaType.TEXT_PLAIN;
            case ".docx" -> DOCX_MEDIA_TYPE;
            default -> throw new BusinessException(ErrorCode.DOCUMENT_PREVIEW_INVALID);
        };
        return new DocumentFileContent(safeFilename(document.getTitle(), documentId, extension), mediaType,
                sourceSize, () -> storageService.read(storagePath));
    }

    private DocumentMetadata findDocument(Long documentId) {
        if (documentId == null || documentId <= 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return documentRepository.findForSemanticRead(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    private long checkedSize(String storagePath) {
        String extension = extension(storagePath);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.DOCUMENT_PREVIEW_INVALID);
        }
        long size = storageService.size(storagePath);
        if (size == 0) {
            throw new BusinessException(ErrorCode.FILE_EMPTY);
        }
        if (size < 0 || size > MAX_PREVIEW_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        return size;
    }

    private String extension(String storagePath) {
        int dot = storagePath == null ? -1 : storagePath.lastIndexOf('.');
        return dot < 0 ? "" : storagePath.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String safeFilename(String title, Long documentId, String extension) {
        String base = title == null ? "" : title
                .replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]+", "-")
                .replaceAll("\\s+", " ")
                .replaceAll("[. ]+$", "")
                .trim();
        if (base.isBlank()) {
            base = "document-" + documentId;
        }
        return base + extension;
    }
}

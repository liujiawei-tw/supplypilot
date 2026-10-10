package com.supplypilot.document.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class DocumentFileValidator {

    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private static final Map<String, Set<String>> CONTENT_TYPES_BY_EXTENSION = Map.of(
            ".pdf", Set.of("application/pdf"),
            ".txt", Set.of("text/plain"),
            ".docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    );

    public String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_EMPTY);
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        String filename = file.getOriginalFilename();
        if (isUnsafeFilename(filename)) {
            throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
        }
        String extension = extensionOf(filename);
        Set<String> allowedContentTypes = CONTENT_TYPES_BY_EXTENSION.get(extension);
        if (allowedContentTypes == null || !allowedContentTypes.contains(normalizeContentType(file.getContentType()))) {
            throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
        }
        return extension;
    }

    private static boolean isUnsafeFilename(String filename) {
        if (filename == null || filename.isBlank() || !filename.equals(filename.trim())) {
            return true;
        }
        return filename.contains("/")
                || filename.contains("\\")
                || filename.matches("^[A-Za-z]:.*")
                || filename.chars().anyMatch(character -> Character.isISOControl(character));
    }

    private static String extensionOf(String filename) {
        int separator = filename.lastIndexOf('.');
        return separator < 0 ? "" : filename.substring(separator).toLowerCase(Locale.ROOT);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameters = contentType.indexOf(';');
        String baseType = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return baseType.trim().toLowerCase(Locale.ROOT);
    }
}

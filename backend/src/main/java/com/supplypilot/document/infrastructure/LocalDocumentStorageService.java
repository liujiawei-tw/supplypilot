package com.supplypilot.document.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.service.DocumentStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(name = "supplypilot.document-storage.type", havingValue = "local", matchIfMissing = true)
public class LocalDocumentStorageService implements DocumentStorageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".txt", ".docx");
    private final Path storageRoot;

    public LocalDocumentStorageService(@Value("${supplypilot.document-storage.path:./data/documents}") String storagePath) {
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    @Override
    public String store(MultipartFile file, String extension) {
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
        }
        String generatedFilename = UUID.randomUUID() + extension;
        Path target = resolveWithinRoot(generatedFilename);
        try {
            Files.createDirectories(storageRoot);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target);
            }
            return generatedFilename;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void delete(String storagePath) {
        Path target = resolveWithinRoot(storagePath);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private Path resolveWithinRoot(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || Path.of(relativePath).isAbsolute()) {
            throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
        }
        Path target = storageRoot.resolve(relativePath).normalize();
        if (!target.startsWith(storageRoot) || target.equals(storageRoot)) {
            throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
        }
        return target;
    }
}

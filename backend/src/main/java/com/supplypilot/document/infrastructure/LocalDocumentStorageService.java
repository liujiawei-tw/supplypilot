package com.supplypilot.document.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
        Path temporaryFile = null;
        try {
            Path verifiedRoot = prepareStorageRoot();
            Path target = resolveWithinRoot(verifiedRoot, generatedFilename);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Generated storage path already exists");
            }

            temporaryFile = Files.createTempFile(verifiedRoot, ".upload-", ".tmp");
            try (InputStream input = file.getInputStream();
                    OutputStream output = Files.newOutputStream(
                            temporaryFile,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS)) {
                input.transferTo(output);
            }

            publish(temporaryFile, target);
            return generatedFilename;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        } finally {
            deleteTemporaryFileQuietly(temporaryFile);
        }
    }

    @Override
    public void delete(String storagePath) {
        try {
            Path target = resolveWithinRoot(prepareStorageRoot(), storagePath);
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private Path prepareStorageRoot() throws IOException {
        Files.createDirectories(storageRoot);
        if (Files.isSymbolicLink(storageRoot)) {
            throw new IOException("Symbolic link storage root is not allowed");
        }
        return storageRoot.toRealPath();
    }

    private Path resolveWithinRoot(Path verifiedRoot, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
        }
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute() || relative.getNameCount() != 1) {
            throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
        }
        Path target = verifiedRoot.resolve(relative).normalize();
        if (!target.startsWith(verifiedRoot) || target.equals(verifiedRoot)) {
            throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
        }
        return target;
    }

    private void publish(Path temporaryFile, Path target) throws IOException {
        try {
            Files.move(temporaryFile, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            if (!Files.isRegularFile(temporaryFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw exception;
            }
            publishWithoutAtomicMove(temporaryFile, target);
        }
    }

    private void publishWithoutAtomicMove(Path temporaryFile, Path target) throws IOException {
        boolean targetCreated = false;
        try (OutputStream output = Files.newOutputStream(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            targetCreated = true;
            Files.copy(temporaryFile, output);
        } catch (IOException exception) {
            if (targetCreated) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            throw exception;
        }
    }

    private void deleteTemporaryFileQuietly(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException ignored) {
            // Preserve the client-safe storage error; cleanup is best effort if the filesystem itself rejects deletion.
        }
    }
}

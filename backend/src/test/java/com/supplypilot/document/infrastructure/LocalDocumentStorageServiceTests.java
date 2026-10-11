package com.supplypilot.document.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDocumentStorageServiceTests {

    @TempDir
    Path tempDirectory;

    @Test
    void storesWithServerGeneratedFilenameAndDeletesBySafeRelativePath() throws Exception {
        LocalDocumentStorageService storage = new LocalDocumentStorageService(tempDirectory.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "client-contract.pdf", "application/pdf", "content".getBytes());

        String storedPath = storage.store(file, ".pdf");

        assertThat(storedPath).endsWith(".pdf").doesNotContain("client-contract");
        assertThat(Path.of(storedPath).isAbsolute()).isFalse();
        assertThat(Files.readString(tempDirectory.resolve(storedPath))).isEqualTo("content");
        try (InputStream input = storage.read(storedPath)) {
            assertThat(input.readAllBytes()).isEqualTo("content".getBytes());
        }

        storage.delete(storedPath);
        assertThat(tempDirectory.resolve(storedPath)).doesNotExist();
    }

    @Test
    void blocksStorageRootEscapeAndAbsolutePaths() {
        LocalDocumentStorageService storage = new LocalDocumentStorageService(tempDirectory.toString());

        assertThatThrownBy(() -> storage.delete("../outside.pdf")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.read("../outside.pdf")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.delete(tempDirectory.resolve("absolute.pdf").toString()))
                .isInstanceOf(BusinessException.class);
        assertThat(tempDirectory.getParent().resolve("outside.pdf")).doesNotExist();
    }

    @Test
    void removesTemporaryFileWhenInputFailsAfterWritingPartialContent() throws Exception {
        Path existingFile = tempDirectory.resolve("existing.pdf");
        Files.writeString(existingFile, "existing-content");
        LocalDocumentStorageService storage = new LocalDocumentStorageService(tempDirectory.toString());
        MockMultipartFile failingFile = new MockMultipartFile(
                "file", "client-contract.pdf", "application/pdf", "partial-content".getBytes()) {
            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    private final byte[] content = "partial-content".getBytes();
                    private int position;

                    @Override
                    public int read() throws IOException {
                        if (position >= 7) {
                            throw new IOException("simulated interrupted upload");
                        }
                        return content[position++];
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        if (position >= 7) {
                            throw new IOException("simulated interrupted upload");
                        }
                        int bytesToRead = Math.min(length, 7 - position);
                        System.arraycopy(content, position, buffer, offset, bytesToRead);
                        position += bytesToRead;
                        return bytesToRead;
                    }
                };
            }
        };

        assertThatThrownBy(() -> storage.store(failingFile, ".pdf"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));

        try (var files = Files.list(tempDirectory)) {
            assertThat(files.toList()).containsExactly(existingFile);
        }
        assertThat(Files.readString(existingFile)).isEqualTo("existing-content");
    }

    @Test
    void rejectsSymbolicLinkStorageRootWhenPlatformSupportsSymbolicLinks() throws Exception {
        Path actualRoot = tempDirectory.resolve("actual-root");
        Files.createDirectory(actualRoot);
        Path symbolicRoot = tempDirectory.resolve("symbolic-root");
        try {
            Files.createSymbolicLink(symbolicRoot, actualRoot);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(
                    false,
                    "Symbolic links are unavailable on this platform: " + exception.getClass().getSimpleName());
            return;
        }
        LocalDocumentStorageService storage = new LocalDocumentStorageService(symbolicRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "client-contract.pdf", "application/pdf", "content".getBytes());

        assertThatThrownBy(() -> storage.store(file, ".pdf"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));
        try (var files = Files.list(actualRoot)) {
            assertThat(files.toList()).isEqualTo(List.of());
        }
    }
}

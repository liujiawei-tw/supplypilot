package com.supplypilot.document.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;

import com.supplypilot.common.exception.BusinessException;
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

        storage.delete(storedPath);
        assertThat(tempDirectory.resolve(storedPath)).doesNotExist();
    }

    @Test
    void blocksStorageRootEscapeAndAbsolutePaths() {
        LocalDocumentStorageService storage = new LocalDocumentStorageService(tempDirectory.toString());

        assertThatThrownBy(() -> storage.delete("../outside.pdf")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.delete(tempDirectory.resolve("absolute.pdf").toString()))
                .isInstanceOf(BusinessException.class);
        assertThat(tempDirectory.getParent().resolve("outside.pdf")).doesNotExist();
    }
}

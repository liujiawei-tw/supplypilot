package com.supplypilot.document.service;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentFileValidatorTests {

    private final DocumentFileValidator validator = new DocumentFileValidator();

    @Test
    void acceptsSupportedExtensionAndMatchingContentType() {
        assertThat(validator.validate(file("contract.pdf", "application/pdf", new byte[] {1}))).isEqualTo(".pdf");
        assertThat(validator.validate(file("notes.TXT", "text/plain; charset=UTF-8", new byte[] {1}))).isEqualTo(".txt");
        assertThat(validator.validate(file("spec.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[] {1})))
                .isEqualTo(".docx");
    }

    @Test
    void rejectsEmptyUnsupportedAndMismatchedFiles() {
        assertError(file("empty.pdf", "application/pdf", new byte[0]), ErrorCode.FILE_EMPTY);
        assertError(file("image.png", "image/png", new byte[] {1}), ErrorCode.INVALID_FILE_FORMAT);
        assertError(file("renamed.pdf", "text/plain", new byte[] {1}), ErrorCode.INVALID_FILE_FORMAT);
        assertError(file("renamed.txt", "application/pdf", new byte[] {1}), ErrorCode.INVALID_FILE_FORMAT);
    }

    @Test
    void rejectsDocumentLargerThanTwentyMegabytes() {
        assertError(file("large.pdf", "application/pdf", new byte[20 * 1024 * 1024 + 1]), ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void rejectsClientPathsAndTraversalNames() {
        assertError(file("../contract.pdf", "application/pdf", new byte[] {1}), ErrorCode.INVALID_FILE_FORMAT);
        assertError(file("..\\contract.pdf", "application/pdf", new byte[] {1}), ErrorCode.INVALID_FILE_FORMAT);
        assertError(file("C:\\temp\\contract.pdf", "application/pdf", new byte[] {1}), ErrorCode.INVALID_FILE_FORMAT);
        assertError(file("/tmp/contract.pdf", "application/pdf", new byte[] {1}), ErrorCode.INVALID_FILE_FORMAT);
    }

    private void assertError(MockMultipartFile file, ErrorCode expected) {
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }

    private MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }
}

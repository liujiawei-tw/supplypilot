package com.supplypilot.document.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.rag.DocumentTextExtractor;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentFileServiceTests {

    private static final long DOCUMENT_ID = 71L;
    @Mock private DocumentMetadataRepository documentRepository;
    @Mock private DocumentStorageService storageService;
    private DocumentFileService service;

    @BeforeEach
    void setUp() {
        service = new DocumentFileService(documentRepository, storageService,
                new DocumentTextExtractor(storageService));
    }

    @Test
    void previewUsesOnlyDocumentIdAndReturnsCompleteUtf8TextWithSafeFilename() throws Exception {
        String source = "供應條件\r\n交期為 30 天\n報價僅供展示";
        when(documentRepository.findForSemanticRead(DOCUMENT_ID))
                .thenReturn(Optional.of(document("供應商/報價\r\n條件", "quote.txt")));
        when(storageService.size("quote.txt")).thenReturn((long) source.getBytes(StandardCharsets.UTF_8).length);
        when(storageService.read("quote.txt"))
                .thenReturn(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));

        DocumentFileContent preview = service.preview(DOCUMENT_ID);

        assertThat(preview.mediaType().toString()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(preview.filename()).doesNotContain("/", "\\", "\r", "\n").endsWith(".txt");
        try (var input = preview.content().getInputStream()) {
            assertThat(input.readAllBytes()).isEqualTo(source.getBytes(StandardCharsets.UTF_8));
        }
        verify(documentRepository).findForSemanticRead(DOCUMENT_ID);
    }

    @Test
    void previewReturnsOriginalPdfBytesWithoutParsingOrIndexing() throws Exception {
        byte[] source = "%PDF-1.7 exact original bytes".getBytes(StandardCharsets.UTF_8);
        when(documentRepository.findForSemanticRead(DOCUMENT_ID))
                .thenReturn(Optional.of(document("Invoice", "invoice.pdf")));
        when(storageService.size("invoice.pdf")).thenReturn((long) source.length);
        when(storageService.read("invoice.pdf")).thenReturn(new ByteArrayInputStream(source));

        DocumentFileContent preview = service.preview(DOCUMENT_ID);

        assertThat(preview.mediaType().toString()).isEqualTo("application/pdf");
        try (var input = preview.content().getInputStream()) {
            assertThat(input.readAllBytes()).isEqualTo(source);
        }
        verify(storageService).read("invoice.pdf");
    }

    @Test
    void previewExtractsDocxParagraphsAndTablesInTheirOriginalOrder() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("文件段落一");
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("表格甲");
            table.getRow(0).getCell(1).setText("表格乙");
            document.createParagraph().createRun().setText("文件段落二");
            document.write(output);
            source = output.toByteArray();
        }
        when(documentRepository.findForSemanticRead(DOCUMENT_ID))
                .thenReturn(Optional.of(document("合約全文", "contract.docx")));
        when(storageService.size("contract.docx")).thenReturn((long) source.length);
        when(storageService.read("contract.docx")).thenReturn(new ByteArrayInputStream(source));

        DocumentFileContent preview = service.preview(DOCUMENT_ID);

        assertThat(preview.mediaType().toString()).isEqualTo("text/plain;charset=UTF-8");
        try (var input = preview.content().getInputStream()) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("文件段落一\n表格甲\t表格乙\n文件段落二\n");
        }
    }

    @Test
    void rejectsMissingDocumentsUnsupportedFormatsAndOversizedStoredFiles() {
        when(documentRepository.findForSemanticRead(99L)).thenReturn(Optional.empty());
        assertBusinessError(() -> service.preview(99L), ErrorCode.DOCUMENT_NOT_FOUND);

        when(documentRepository.findForSemanticRead(DOCUMENT_ID))
                .thenReturn(Optional.of(document("Unexpected", "raw.bin")));
        assertBusinessError(() -> service.preview(DOCUMENT_ID), ErrorCode.DOCUMENT_PREVIEW_INVALID);
        verify(storageService, never()).size("raw.bin");

        when(documentRepository.findForSemanticRead(DOCUMENT_ID))
                .thenReturn(Optional.of(document("Oversized", "large.txt")));
        when(storageService.size("large.txt")).thenReturn(DocumentFileValidator.MAX_FILE_BYTES + 1);
        assertBusinessError(() -> service.preview(DOCUMENT_ID), ErrorCode.FILE_TOO_LARGE);
        verify(storageService, never()).read("large.txt");
    }

    @Test
    void rejectsMalformedUtf8WithAClientSafePreviewError() {
        when(documentRepository.findForSemanticRead(DOCUMENT_ID))
                .thenReturn(Optional.of(document("Broken", "broken.txt")));
        when(storageService.size("broken.txt")).thenReturn(2L);
        when(storageService.read("broken.txt"))
                .thenReturn(new ByteArrayInputStream(new byte[] {(byte) 0xC3, (byte) 0x28}));

        assertBusinessError(() -> service.preview(DOCUMENT_ID), ErrorCode.DOCUMENT_PREVIEW_INVALID);
    }

    private DocumentMetadata document(String title, String storagePath) {
        return DocumentMetadata.processing(DocumentType.PROCUREMENT_SOP, null, title, "v1",
                storagePath, User.create("preview-user", "hash", "Preview User", UserRole.ADMIN,
                        java.time.Instant.parse("2026-10-25T00:00:00Z")),
                java.time.Instant.parse("2026-10-25T00:00:00Z"));
    }

    private void assertBusinessError(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, ErrorCode expected) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}

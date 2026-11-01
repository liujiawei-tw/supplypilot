package com.supplypilot.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocumentPreviewServiceTests {
    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final DocumentPreviewService previews = new DocumentPreviewService(storage);

    @Test
    void returnsCompleteUtf8TextWithoutNormalization() {
        String source = "  第一行  \r\n第二行\t保持\n";
        when(storage.read("complete.txt"))
                .thenReturn(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));

        assertThat(previews.extractPreviewText("complete.txt")).isEqualTo(source);
    }

    @Test
    void preservesParagraphAndTableOrderInDocxPreview() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("paragraph before table");
            document.createTable(1, 1).getRow(0).getCell(0).setText("middle table cell");
            document.createParagraph().createRun().setText("paragraph after table");
            document.write(output);
            source = output.toByteArray();
        }
        when(storage.read("ordered.docx")).thenAnswer(invocation -> new ByteArrayInputStream(source));

        assertThat(previews.extractPreviewText("ordered.docx"))
                .isEqualTo("paragraph before table\nmiddle table cell\nparagraph after table\n");
    }

    @Test
    void rejectsInvalidUtf8() {
        when(storage.read("invalid-utf8.txt"))
                .thenReturn(new ByteArrayInputStream(new byte[] {(byte) 0xC3, (byte) 0x28}));

        assertThatThrownBy(() -> previews.extractPreviewText("invalid-utf8.txt"))
                .isInstanceOf(DocumentPreviewException.class);
    }

    @Test
    void rejectsDocxPackagesWithTooManyZipEntries() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int index = 0; index < 4_097; index++) {
                zip.putNextEntry(new ZipEntry("part-" + index));
                zip.closeEntry();
            }
        }
        when(storage.read("many-entries.docx"))
                .thenReturn(new ByteArrayInputStream(output.toByteArray()));

        assertThatThrownBy(() -> previews.extractPreviewText("many-entries.docx"))
                .isInstanceOf(DocumentPreviewException.class)
                .hasMessageContaining("too many entries");
    }
}

package com.supplypilot.document.rag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import com.supplypilot.document.service.DocumentStorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentTextExtractorTests {

    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final DocumentTextExtractor extractor = new DocumentTextExtractor(storage);

    @Test
    void extractsUtf8Txt() {
        when(storage.read("fixture.txt")).thenReturn(new ByteArrayInputStream("payment term net 30".getBytes(StandardCharsets.UTF_8)));
        assertThat(extractor.extract("fixture.txt")).isEqualTo("payment term net 30");
    }

    @Test
    void extractsPdf() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Supplier payment term is net 30 days");
                content.endText();
            }
            document.save(output);
        }
        when(storage.read("fixture.pdf")).thenReturn(new ByteArrayInputStream(output.toByteArray()));
        assertThat(extractor.extract("fixture.pdf")).contains("payment term", "net 30");
    }

    @Test
    void extractsDocxParagraphsAndTables() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Quality requirement AQL 1.0");
            var table = document.createTable(1, 1);
            table.getRow(0).getCell(0).setText("inspection before delivery");
            document.write(output);
        }
        when(storage.read("fixture.docx")).thenReturn(new ByteArrayInputStream(output.toByteArray()));
        assertThat(extractor.extract("fixture.docx")).contains("AQL 1.0", "inspection before delivery");
    }

    @Test
    void rejectsCorruptAndTextlessDocuments() {
        when(storage.read("corrupt.pdf")).thenReturn(new ByteArrayInputStream("not pdf".getBytes(StandardCharsets.UTF_8)));
        when(storage.read("empty.txt")).thenReturn(new ByteArrayInputStream(" \n\t ".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> extractor.extract("corrupt.pdf")).isInstanceOf(DocumentParsingException.class);
        assertThatThrownBy(() -> extractor.extract("empty.txt")).isInstanceOf(DocumentParsingException.class);
    }
}

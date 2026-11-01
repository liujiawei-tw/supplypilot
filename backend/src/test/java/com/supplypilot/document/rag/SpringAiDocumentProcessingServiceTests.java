package com.supplypilot.document.rag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.supplypilot.document.service.DocumentFileValidator;
import com.supplypilot.document.service.DocumentStorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiDocumentProcessingServiceTests {

    private final DocumentStorageService storage = mock(DocumentStorageService.class);

    @Test
    void readsUtf8TxtThroughSpringAiTextReader() {
        String text = "付款條件為月結三十天；交期為 14 days.";
        when(storage.read("fixture.txt"))
                .thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));

        List<SpringAiDocumentProcessingService.SourceChunk> chunks = service(800, 80, 10)
                .readAndSplit("fixture.txt");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).isEqualTo(text);
        assertThat(text.substring(chunks.getFirst().startOffset(), chunks.getFirst().endOffset()))
                .isEqualTo(chunks.getFirst().content());
    }

    @Test
    void rejectsMalformedUtf8Txt() {
        when(storage.read("invalid.txt"))
                .thenReturn(new ByteArrayInputStream(new byte[] {(byte) 0xC3, (byte) 0x28}));

        assertThatThrownBy(() -> service(800, 80, 10).readAndSplit("invalid.txt"))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("not valid UTF-8");
    }

    @Test
    void readsPdfAndDocxWithSpringAiTikaReader() throws Exception {
        byte[] pdf = pdf("Supplier payment term is net 30 days");
        byte[] docx = docx("付款條件為月結三十天", "交貨前須完成品質檢驗");
        when(storage.read("terms.pdf")).thenReturn(new ByteArrayInputStream(pdf));
        when(storage.read("quality.docx")).thenReturn(new ByteArrayInputStream(docx));
        SpringAiDocumentProcessingService processor = service(800, 80, 10);

        assertThat(processor.extract("terms.pdf")).contains("payment term", "net 30");
        assertThat(processor.extract("quality.docx")).contains("付款條件", "品質檢驗");
    }

    @Test
    void chunksChineseAndEnglishWithExactExtractedTextOffsetsAndConfiguredOverlap() {
        String paragraph = "付款條件為月結三十天，交期為 fourteen calendar days. "
                + "The supplier must provide inspection records before delivery. ";
        String text = paragraph.repeat(80);
        List<SpringAiDocumentProcessingService.SourceChunk> chunks = service(256, 32, 100).split(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (int index = 0; index < chunks.size(); index++) {
            SpringAiDocumentProcessingService.SourceChunk chunk = chunks.get(index);
            assertThat(chunk.index()).isEqualTo(index);
            assertThat(chunk.startOffset()).isGreaterThanOrEqualTo(0);
            assertThat(chunk.endOffset()).isLessThanOrEqualTo(text.length());
            assertThat(chunk.endOffset()).isGreaterThan(chunk.startOffset());
            assertThat(chunk.content()).isEqualTo(text.substring(chunk.startOffset(), chunk.endOffset()));
        }
        assertThat(chunks.get(1).startOffset()).isLessThan(chunks.get(0).endOffset());
        String reconstructed = chunks.stream().map(SpringAiDocumentProcessingService.SourceChunk::content)
                .collect(java.util.stream.Collectors.joining());
        assertThat(reconstructed).contains("付款條件", "The supplier");
    }

    @Test
    void rejectsInputWhenSpringAiSplitterWouldExceedConfiguredChunkLimit() {
        String text = ("此段文字包含付款條件 net 30 days 與交期 fourteen days. ").repeat(400);

        assertThatThrownBy(() -> service(160, 16, 1).split(text))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("chunk limit");
    }

    @Test
    void rejectsStoredFileAboveParserByteLimit() {
        byte[] oversized = new byte[(int) DocumentFileValidator.MAX_FILE_BYTES + 1];
        when(storage.read("large.txt")).thenReturn(new ByteArrayInputStream(oversized));

        assertThatThrownBy(() -> service(800, 80, 10).readAndSplit("large.txt"))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("parser limit");
    }

    @Test
    void rejectsDocxWithInvalidZipAndPackageWithTooManyEntries() throws Exception {
        when(storage.read("broken.docx"))
                .thenReturn(new ByteArrayInputStream("not a zip package".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int index = 0; index < 4_097; index++) {
                zip.putNextEntry(new ZipEntry("part-" + index));
                zip.closeEntry();
            }
        }
        when(storage.read("too-many.docx")).thenReturn(new ByteArrayInputStream(output.toByteArray()));
        SpringAiDocumentProcessingService processor = service(800, 80, 10);

        assertThatThrownBy(() -> processor.extract("broken.docx"))
                .isInstanceOf(DocumentParsingException.class);
        assertThatThrownBy(() -> processor.extract("too-many.docx"))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("too many entries");
    }

    @Test
    void rejectsDocxPackageWhoseExpandedContentsExceedParserLimit() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] block = new byte[8_192];
            for (int written = 0; written < DocumentFileValidator.MAX_FILE_BYTES + 1;) {
                int count = (int) Math.min(block.length, DocumentFileValidator.MAX_FILE_BYTES + 1 - written);
                zip.write(block, 0, count);
                written += count;
            }
            zip.closeEntry();
        }
        when(storage.read("expanded.docx")).thenReturn(new ByteArrayInputStream(output.toByteArray()));

        assertThatThrownBy(() -> service(800, 80, 10).extract("expanded.docx"))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("exceeds parser limit");
    }

    private SpringAiDocumentProcessingService service(int chunkSize, int overlap, int maxChunks) {
        return new SpringAiDocumentProcessingService(storage,
                new RagChunkingProperties(chunkSize, overlap, maxChunks));
    }

    private byte[] pdf(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText(text);
                content.endText();
            }
            document.save(output);
        }
        return output.toByteArray();
    }

    private byte[] docx(String paragraph, String tableCell) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(paragraph);
            document.createTable(1, 1).getRow(0).getCell(0).setText(tableCell);
            document.write(output);
        }
        return output.toByteArray();
    }
}

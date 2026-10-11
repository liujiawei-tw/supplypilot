package com.supplypilot.document.rag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.supplypilot.document.service.DocumentStorageService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;

@Component
public class DocumentTextExtractor {

    private static final int MAX_PARSE_BYTES = 20 * 1024 * 1024;
    private final DocumentStorageService storage;

    public DocumentTextExtractor(DocumentStorageService storage) {
        this.storage = storage;
    }

    public String extract(String storagePath) {
        String extension = extension(storagePath);
        try (InputStream input = storage.read(storagePath)) {
            String text = switch (extension) {
                case ".txt" -> decodeUtf8(readLimited(input));
                case ".pdf" -> extractPdf(readLimited(input));
                case ".docx" -> extractDocx(input);
                default -> throw new DocumentParsingException("Unsupported stored document format");
            };
            String normalized = text.replace('\u0000', ' ').replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                    .replaceAll(" *\\n+ *", "\n").trim();
            if (normalized.isBlank()) {
                throw new DocumentParsingException("Document contains no recognizable text");
            }
            return normalized;
        } catch (DocumentParsingException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocumentParsingException("Document could not be parsed", exception);
        }
    }

    private String extractPdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new DocumentParsingException("Encrypted PDF is not supported");
            }
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(InputStream input) throws IOException {
        try (XWPFDocument document = new XWPFDocument(input)) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append('\n');
            }
            for (XWPFTable table : document.getTables()) {
                table.getRows().forEach(row -> row.getTableCells().forEach(cell ->
                        text.append(cell.getText()).append('\n')));
            }
            return text.toString();
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_PARSE_BYTES) {
                throw new DocumentParsingException("Stored document exceeds parser limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new DocumentParsingException("TXT document is not valid UTF-8", exception);
        }
    }

    private String extension(String storagePath) {
        int dot = storagePath.lastIndexOf('.');
        return dot < 0 ? "" : storagePath.substring(dot).toLowerCase(Locale.ROOT);
    }
}

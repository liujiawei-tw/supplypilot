package com.supplypilot.document.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Service;

/** Reads user-facing TXT and DOCX previews without participating in RAG ingestion or indexing. */
@Service
public class DocumentPreviewService {

    private static final int MAX_PREVIEW_BYTES = (int) DocumentFileValidator.MAX_FILE_BYTES;
    private static final int MAX_DOCX_ZIP_ENTRIES = 4_096;

    private final DocumentStorageService storage;

    public DocumentPreviewService(DocumentStorageService storage) {
        this.storage = storage;
    }

    /**
     * TXT bytes are returned as-is after strict UTF-8 decoding. DOCX body elements retain their
     * document order, including interleaved paragraphs and tables.
     */
    public String extractPreviewText(String storagePath) {
        String extension = extension(storagePath);
        if (!extension.equals(".txt") && !extension.equals(".docx")) {
            throw new DocumentPreviewException("Preview text is not supported for this document format");
        }
        try (InputStream input = storage.read(storagePath)) {
            byte[] bytes = readPreviewLimited(input);
            return switch (extension) {
                case ".txt" -> decodeUtf8(bytes);
                case ".docx" -> extractDocxPreview(bytes);
                default -> throw new DocumentPreviewException("Unsupported preview format");
            };
        } catch (DocumentPreviewException exception) {
            throw exception;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocumentPreviewException("Document preview could not be parsed", exception);
        }
    }

    private String extractDocxPreview(byte[] bytes) throws IOException {
        assertDocxExpandedSizeWithinLimit(bytes);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            for (var element : document.getBodyElements()) {
                if (element instanceof org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph) {
                    appendPreviewText(text, paragraph.getText());
                    text.append('\n');
                } else if (element instanceof XWPFTable table) {
                    for (var row : table.getRows()) {
                        for (int cellIndex = 0; cellIndex < row.getTableCells().size(); cellIndex++) {
                            if (cellIndex > 0) text.append('\t');
                            appendPreviewText(text, row.getTableCells().get(cellIndex).getText());
                        }
                        text.append('\n');
                    }
                }
                ensurePreviewTextWithinLimit(text);
            }
            String preview = text.toString();
            if (preview.getBytes(StandardCharsets.UTF_8).length > MAX_PREVIEW_BYTES) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
            }
            return preview;
        }
    }

    private void assertDocxExpandedSizeWithinLimit(byte[] bytes) throws IOException {
        long expandedBytes = 0;
        int entryCount = 0;
        boolean hasEntries = false;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                hasEntries = true;
                if (++entryCount > MAX_DOCX_ZIP_ENTRIES) {
                    throw new DocumentPreviewException("DOCX package contains too many entries");
                }
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    expandedBytes += read;
                    if (expandedBytes > MAX_PREVIEW_BYTES) {
                        throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
                    }
                }
                zip.closeEntry();
            }
        }
        if (!hasEntries) {
            throw new DocumentPreviewException("DOCX document is not a valid ZIP package");
        }
    }

    private void appendPreviewText(StringBuilder target, String value) {
        target.append(value);
        ensurePreviewTextWithinLimit(target);
    }

    private void ensurePreviewTextWithinLimit(StringBuilder text) {
        if (text.length() > MAX_PREVIEW_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
    }

    private byte[] readPreviewLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_PREVIEW_BYTES) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
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
            throw new DocumentPreviewException("TXT document is not valid UTF-8", exception);
        }
    }

    private String extension(String storagePath) {
        int dot = storagePath.lastIndexOf('.');
        return dot < 0 ? "" : storagePath.substring(dot).toLowerCase(Locale.ROOT);
    }
}

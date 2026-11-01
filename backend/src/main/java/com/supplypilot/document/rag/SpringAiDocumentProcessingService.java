package com.supplypilot.document.rag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jakarta.annotation.PreDestroy;
import com.supplypilot.document.service.DocumentFileValidator;
import com.supplypilot.document.service.DocumentStorageService;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

/** Reads and token-splits approved document sources while retaining verifiable extracted-text offsets. */
@Service
public class SpringAiDocumentProcessingService {

    private static final int MAX_PARSE_BYTES = (int) DocumentFileValidator.MAX_FILE_BYTES;
    private static final int MAX_DOCX_ZIP_ENTRIES = 4_096;
    private static final List<Character> PUNCTUATION = List.of(
            '.', '?', '!', '\n', '。', '？', '！', '；', '，', '、');

    private final DocumentStorageService storage;
    private final RagChunkingProperties properties;
    private final ExecutorService parserExecutor = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());

    public SpringAiDocumentProcessingService(DocumentStorageService storage, RagChunkingProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    public List<SourceChunk> readAndSplit(String storagePath) {
        String text = extract(storagePath);
        return split(text);
    }

    public String extract(String storagePath) {
        String extension = extension(storagePath);
        if (!List.of(".txt", ".pdf", ".docx").contains(extension)) {
            throw new DocumentParsingException("Unsupported stored document format");
        }
        try (InputStream input = storage.read(storagePath)) {
            byte[] bytes = readLimited(input);
            if (extension.equals(".txt")) return normalize(readText(bytes));
            if (extension.equals(".docx")) assertDocxExpandedSizeWithinLimit(bytes);

            ByteArrayResource resource = resource(bytes, storagePath);
            verifyDetectedType(extension, bytes);
            List<Document> documents = parseWithTimeout(resource);
            String extracted = documents.stream().map(Document::getText)
                    .filter(value -> value != null && !value.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
            return normalize(extracted);
        } catch (DocumentParsingException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocumentParsingException("Document could not be parsed", exception);
        }
    }

    public List<SourceChunk> split(String extractedText) {
        String source = normalize(extractedText);
        if (source.isBlank()) throw new DocumentParsingException("Document contains no recognizable text");

        int splitterChunkSize = Math.max(1, properties.chunkSize() - properties.overlapCharacters());
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(splitterChunkSize)
                .withMinChunkSizeChars(120)
                .withMinChunkLengthToEmbed(0)
                .withMaxNumChunks(properties.maxChunks())
                .withKeepSeparator(true)
                .withPunctuationMarks(PUNCTUATION)
                .build();
        List<Document> documents = splitter.apply(List.of(new Document(source)));
        if (documents.isEmpty() || documents.size() > properties.maxChunks()) {
            throw new DocumentParsingException("Document exceeds the configured chunk limit");
        }

        List<SourceChunk> result = new ArrayList<>(documents.size());
        int searchFrom = 0;
        int previousStart = -1;
        for (int index = 0; index < documents.size(); index++) {
            String content = documents.get(index).getText();
            int start = source.indexOf(content, searchFrom);
            if (start < 0) {
                throw new DocumentParsingException("Spring AI chunk could not be mapped to extracted source text");
            }
            int end = start + content.length();
            if (end > source.length()) {
                throw new DocumentParsingException("Spring AI chunk source position is invalid");
            }
            int chunkStart = index == 0 ? start
                    : Math.max(0, start - properties.overlapCharacters());
            if (previousStart >= 0) chunkStart = Math.max(chunkStart, previousStart);
            String overlapped = source.substring(chunkStart, end);
            result.add(new SourceChunk(index, chunkStart, end, overlapped));
            previousStart = chunkStart;
            searchFrom = end;
        }
        return List.copyOf(result);
    }

    private String readText(byte[] bytes) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            TextReader reader = new TextReader(resource(bytes, "document.txt"));
            reader.setCharset(StandardCharsets.UTF_8);
            List<Document> documents = reader.get();
            if (documents.isEmpty()) return decoded;
            return documents.stream().map(Document::getText).filter(value -> value != null)
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (CharacterCodingException exception) {
            throw new DocumentParsingException("TXT document is not valid UTF-8", exception);
        }
    }

    @PreDestroy
    void closeParserExecutor() {
        parserExecutor.shutdownNow();
    }

    private void verifyDetectedType(String extension, byte[] bytes) {
        String detected = new Tika().detect(bytes);
        boolean supported = switch (extension) {
            case ".pdf" -> "application/pdf".equals(detected);
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    .equals(detected);
            default -> false;
        };
        if (!supported) throw new DocumentParsingException("Stored document type does not match its extension");
    }

    private List<Document> parseWithTimeout(ByteArrayResource resource) {
        Future<List<Document>> pending = parserExecutor.submit(() -> new TikaDocumentReader(resource,
                new org.apache.tika.sax.BodyContentHandler(MAX_PARSE_BYTES),
                new ExtractedTextFormatter.Builder().build()).get());
        try {
            return pending.get(properties.parserTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw new DocumentParsingException("Document parsing timed out", failure);
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new DocumentParsingException("Document parsing was interrupted", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof DocumentParsingException parsing) throw parsing;
            throw new DocumentParsingException("Document could not be parsed", cause);
        }
    }

    private ByteArrayResource resource(byte[] bytes, String storagePath) {
        String filename = storagePath.substring(storagePath.lastIndexOf('/') + 1)
                .substring(storagePath.substring(storagePath.lastIndexOf('/') + 1).lastIndexOf('\\') + 1);
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_PARSE_BYTES) throw new DocumentParsingException("Stored document exceeds parser limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void assertDocxExpandedSizeWithinLimit(byte[] bytes) throws IOException {
        long expandedBytes = 0;
        int entryCount = 0;
        boolean hasEntries = false;
        byte[] buffer = new byte[8_192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                hasEntries = true;
                if (++entryCount > MAX_DOCX_ZIP_ENTRIES) {
                    throw new DocumentParsingException("DOCX package contains too many entries");
                }
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    expandedBytes += read;
                    if (expandedBytes > MAX_PARSE_BYTES) {
                        throw new DocumentParsingException("DOCX package exceeds parser limit");
                    }
                }
                zip.closeEntry();
            }
        }
        if (!hasEntries) throw new DocumentParsingException("DOCX document is not a valid ZIP package");
    }

    private String normalize(String text) {
        if (text == null) return "";
        String normalized = text.replace('\u0000', ' ').replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n+ *", "\n").trim();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_PARSE_BYTES) {
            throw new DocumentParsingException("Extracted document exceeds parser limit");
        }
        return normalized;
    }

    private String extension(String storagePath) {
        int dot = storagePath.lastIndexOf('.');
        return dot < 0 ? "" : storagePath.substring(dot).toLowerCase(Locale.ROOT);
    }

    public record SourceChunk(int index, int startOffset, int endOffset, String content) { }
}

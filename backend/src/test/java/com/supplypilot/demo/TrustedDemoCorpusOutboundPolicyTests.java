package com.supplypilot.demo;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.rag.DocumentTextExtractor;
import com.supplypilot.document.rag.TextChunker;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.SemanticIndexJobRepository;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.service.DocumentStorageService;
import com.supplypilot.supplier.domain.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the production trusted-review boundary against every pinned baseline source. */
class TrustedDemoCorpusOutboundPolicyTests {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

    @TempDir Path temp;

    @Test
    void all115PinnedBaselineSourcesAreClassifiedAndReportedByDocumentKey() throws Exception {
        Path samples = findSamples();
        Path master = samples.getParent().resolve("demo-master-data.json");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode catalog = mapper.readTree(Files.readAllBytes(samples.resolve("corpus-catalog.json")));
        List<JsonNode> rows = StreamSupport.stream(catalog.path("documents").spliterator(), false)
                .filter(row -> "SEED_BASELINE".equals(row.path("classification").asText())).toList();
        assertThat(rows).hasSize(115);

        Map<Long, DocumentMetadata> documentsById = new HashMap<>();
        Map<Long, List<DocumentChunk>> chunksById = new HashMap<>();
        Map<String, String> sourceByStoragePath = new HashMap<>();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT DATABASE()"), eq(String.class))).thenReturn("supplypilot_demo");
        DocumentMetadataRepository documents = mock(DocumentMetadataRepository.class);
        when(documents.findByIdForUpdateWithSupplier(anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(documentsById.get(invocation.getArgument(0))));
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        when(chunks.findByDocumentIdOrderByChunkIndex(anyLong()))
                .thenAnswer(invocation -> chunksById.getOrDefault(invocation.getArgument(0), List.of()));
        DocumentStorageService storage = mock(DocumentStorageService.class);
        when(storage.read(anyString())).thenAnswer(invocation -> {
            String source = sourceByStoragePath.get(invocation.getArgument(0));
            return source == null ? null : new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
        });
        DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
        when(extractor.extract(anyString())).thenAnswer(invocation -> sourceByStoragePath.get(invocation.getArgument(0)));
        SemanticIndexJobRepository jobs = mock(SemanticIndexJobRepository.class);

        DemoProperties demo = new DemoProperties();
        demo.setSamplesPath(samples.toString());
        demo.setMasterDataPath(master.toString());
        SemanticProperties semantic = new SemanticProperties(
                "jdbc:postgresql://pgvector:5432/supplypilot_semantic_demo", "demo", "demo",
                "embedding-model", 3, "gen-v1", true, 10, 0.5, Duration.ofSeconds(2));
        OutboundDocumentPolicy policy = new OutboundDocumentPolicy();
        DemoCorpusTrustedApprovalService service = new DemoCorpusTrustedApprovalService(demo,
                temp.resolve("demo-document-storage").toString(), jdbc, documents, chunks, storage,
                jobs, semantic, policy, Clock.fixed(NOW, ZoneOffset.UTC), mapper, extractor, new TextChunker());

        TextChunker chunker = new TextChunker();
        int approved = 0;
        long id = 1000;
        for (JsonNode row : rows) {
            DemoCorpusSyncService.BaselineEntry entry = entry(row);
            Path sourcePath = samples.resolve(Path.of(entry.filename()).getFileName());
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            String storagePath = "trusted/" + entry.documentKey() + ".txt";
            sourceByStoragePath.put(storagePath, source);
            Supplier supplier = supplier(entry);
            DocumentMetadata document = DocumentMetadata.demoBaselineProcessing(entry.type(), supplier,
                    entry.title(), entry.version(), storagePath, entry.datasetKey(), entry.documentKey(),
                    entry.themeKey(), entry.corpusVersion(), entry.sourceSha256(), entry.provenanceOrigin(), NOW);
            ReflectionTestUtils.setField(document, "id", id);
            document.markReady(NOW.plusSeconds(1));
            documentsById.put(id, document);
            List<DocumentChunk> documentChunks = chunker.chunk(source).stream()
                    .map(chunk -> DocumentChunk.create(document, chunk.index(), chunk.startOffset(),
                            chunk.endOffset(), chunk.content(), "[]", NOW)).toList();
            chunksById.put(id, documentChunks);

            var result = service.review(id, entry);
            String expected = "APPROVED";
            String actual = result.status().name();
            System.out.printf("DEMO_OUTBOUND_POLICY|key=%s|expected=%s|actual=%s|reason=%s%n",
                    entry.documentKey(), expected, actual, result.reason());
            assertThat(actual).as("key=%s expected=%s actual=%s reason=%s", entry.documentKey(),
                    expected, actual, result.reason()).isEqualTo(expected);
            assertThat(result.jobQueued()).as("key=%s reason=%s", entry.documentKey(), result.reason()).isTrue();
            approved++;
            id++;
        }
        assertThat(approved).isEqualTo(115);
    }

    private static Supplier supplier(DemoCorpusSyncService.BaselineEntry entry) {
        if (entry.supplierCode() == null) return null;
        Supplier supplier = mock(Supplier.class);
        when(supplier.getCode()).thenReturn(entry.supplierCode());
        when(supplier.getName()).thenReturn(entry.supplierName());
        return supplier;
    }

    private static DemoCorpusSyncService.BaselineEntry entry(JsonNode row) {
        JsonNode provenance = row.path("provenance");
        List<String> supplierCodes = strings(row, "supplierCodes");
        List<String> supplierNames = strings(row, "supplierNames");
        return new DemoCorpusSyncService.BaselineEntry("SP-901", row.path("documentKey").asText(),
                DocumentType.valueOf(row.path("type").asText()), row.path("themeKey").asText(),
                row.path("version").asText(), row.path("title").asText(), row.path("filename").asText(),
                row.path("corpusVersion").asText(), row.path("sourceSha256").asText(),
                row.path("classification").asText(), supplierCodes.isEmpty() ? null : supplierCodes.getFirst(),
                supplierNames.isEmpty() ? null : supplierNames.getFirst(), strings(row, "materialCodes"),
                strings(row, "materialNames"), strings(row, "fixtureKeys"), provenance.path("kind").asText(),
                provenance.path("datasetKey").asText(), provenance.path("corpusVersion").asText(),
                provenance.path("origin").asText(), row.path("approvalPolicy").asText(),
                row.path("outboundPolicy").asText());
    }

    private static List<String> strings(JsonNode node, String field) {
        return StreamSupport.stream(node.path(field).spliterator(), false).map(JsonNode::asText).toList();
    }

    private static Path findSamples() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            Path samples = candidate.resolve("samples/documents");
            if (Files.isRegularFile(samples.resolve("corpus-catalog.json"))) return samples;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository demo corpus is not available to this test");
    }
}

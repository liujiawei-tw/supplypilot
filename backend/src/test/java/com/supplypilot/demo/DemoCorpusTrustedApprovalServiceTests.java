package com.supplypilot.demo;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.rag.DocumentTextExtractor;
import com.supplypilot.document.rag.TextChunker;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.SemanticIndexJobRepository;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.service.DocumentStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoCorpusTrustedApprovalServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final String DOCUMENT_KEY = "DOC-SOP-LIFECYCLE";

    @TempDir Path temp;
    private DemoProperties demo;
    private JdbcTemplate jdbc;
    private DocumentMetadataRepository documents;
    private DocumentChunkRepository chunks;
    private DocumentStorageService storage;
    private SemanticIndexJobRepository jobs;
    private SemanticProperties semantic;
    private OutboundDocumentPolicy policy;
    private DocumentTextExtractor extractor;
    private DemoCorpusSyncService.BaselineEntry entry;
    private DocumentMetadata document;
    private List<DocumentChunk> sourceChunks;
    private String source;
    private Path samples;

    @BeforeEach
    void setUp() throws Exception {
        samples = Files.createDirectory(temp.resolve("samples"));
        Path repositorySamples = findRepositorySamples();
        byte[] catalogBytes = Files.readAllBytes(repositorySamples.resolve("corpus-catalog.json"));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode catalog = mapper.readTree(catalogBytes);
        JsonNode row = StreamSupport.stream(catalog.path("documents").spliterator(), false)
                .filter(candidate -> DOCUMENT_KEY.equals(candidate.path("documentKey").asText()))
                .findFirst().orElseThrow();
        entry = entry(row);
        Path sourceFile = repositorySamples.resolve(Path.of(entry.filename()).getFileName());
        source = Files.readString(sourceFile, StandardCharsets.UTF_8);
        Files.write(samples.resolve("corpus-catalog.json"), catalogBytes);
        Files.copy(sourceFile, samples.resolve(sourceFile.getFileName()));

        demo = new DemoProperties();
        demo.setSamplesPath(samples.toString());
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT DATABASE()"), eq(String.class))).thenReturn("supplypilot_demo");
        documents = mock(DocumentMetadataRepository.class);
        chunks = mock(DocumentChunkRepository.class);
        storage = mock(DocumentStorageService.class);
        jobs = mock(SemanticIndexJobRepository.class);
        extractor = mock(DocumentTextExtractor.class);
        when(extractor.extract("stored/source.txt")).thenReturn(source.trim());
        semantic = properties("jdbc:postgresql://pgvector:5432/supplypilot_semantic_demo");
        policy = new OutboundDocumentPolicy();

        document = DocumentMetadata.demoBaselineProcessing(entry.type(), null,
                entry.title(), entry.version(), "stored/source.txt", "SP-901", DOCUMENT_KEY,
                entry.themeKey(), entry.corpusVersion(), entry.sourceSha256(), entry.provenanceOrigin(), NOW);
        ReflectionTestUtils.setField(document, "id", 44L);
        document.markReady(NOW.plusSeconds(1));
        TextChunker chunker = new TextChunker();
        sourceChunks = chunker.chunk(source.trim()).stream().map(c -> DocumentChunk.create(document,
                c.index(), c.startOffset(), c.endOffset(), c.content(), "[]", NOW)).toList();
        when(documents.findByIdForUpdateWithSupplier(44L)).thenReturn(Optional.of(document));
        when(chunks.findByDocumentIdOrderByChunkIndex(44L)).thenReturn(sourceChunks);
        when(storage.read("stored/source.txt"))
                .thenReturn(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingSemanticDatabaseRecordsReviewButDoesNotQueue() {
        DemoCorpusTrustedApprovalService service = service(null);

        var result = service.review(44L, entry);

        assertThat(result.status()).isEqualTo(SemanticReviewStatus.REQUIRES_REVIEW);
        assertThat(result.reason()).isEqualTo("SEMANTIC_DEMO_DATABASE_NOT_CONFIGURED");
        assertThat(result.jobQueued()).isFalse();
        assertThat(document.getStatus().name()).isEqualTo("READY");
        assertThat(document.getSemanticReviewActorType()).isEqualTo("SYSTEM");
        verify(jobs, never()).enqueue(anyLong(), any(), any(), any(), anyLong(), any(Instant.class));
    }

    @Test
    void exactPinnedCatalogAndAllowedDemoHostQueueExistingDurableJob() {
        DemoCorpusTrustedApprovalService service = service("jdbc:postgresql://pgvector:5432/supplypilot_semantic_demo");

        var result = service.review(44L, entry);

        assertThat(result.status()).isEqualTo(SemanticReviewStatus.APPROVED);
        assertThat(result.jobQueued()).isTrue();
        assertThat(document.getSemanticApprovedBy()).isNull();
        assertThat(document.getSemanticReviewActor()).isEqualTo("TRUSTED_DEMO_CORPUS");
        verify(jobs).enqueue(eq(44L), eq("1.0"), any(), eq("gen-v1"), eq(1L), any(Instant.class));
    }

    @Test
    void rejectsForgedCatalogEntryAndUnapprovedExternalHost() {
        var forged = new DemoCorpusSyncService.BaselineEntry(entry.datasetKey(), entry.documentKey(), entry.type(),
                entry.themeKey(), entry.version(), "different title", entry.filename(), entry.corpusVersion(),
                entry.sourceSha256(), entry.classification(), null, null, entry.materialCodes(), entry.materialNames(),
                entry.fixtureKeys(), entry.provenanceKind(), entry.provenanceDatasetKey(),
                entry.provenanceCorpusVersion(), entry.provenanceOrigin(), entry.approvalPolicy(), entry.outboundPolicy());
        assertThatThrownBy(() -> service("jdbc:postgresql://pgvector:5432/supplypilot_semantic_demo").review(44L, forged))
                .isInstanceOf(IllegalStateException.class).hasMessage("TRUSTED_CATALOG_ENTRY_MISMATCH");

        document = DocumentMetadata.demoBaselineProcessing(entry.type(), null,
                entry.title(), entry.version(), "stored/source.txt", "SP-901", DOCUMENT_KEY,
                entry.themeKey(), entry.corpusVersion(), entry.sourceSha256(), entry.provenanceOrigin(), NOW);
        ReflectionTestUtils.setField(document, "id", 44L);
        document.markReady(NOW.plusSeconds(1));
        when(documents.findByIdForUpdateWithSupplier(44L)).thenReturn(Optional.of(document));
        var blocked = service("jdbc:postgresql://production.example/supplypilot_semantic_demo").review(44L, entry);
        assertThat(blocked.status()).isEqualTo(SemanticReviewStatus.REQUIRES_REVIEW);
        assertThat(blocked.reason()).isEqualTo("SEMANTIC_DEMO_DATABASE_REJECTED");
        verify(jobs, never()).enqueue(anyLong(), any(), any(), any(), anyLong(), any(Instant.class));
    }

    @Test
    void rejectsModifiedCatalogEvenWhenItsEnvelopeAndEntryStillMatch() throws Exception {
        Path catalog = samples.resolve("corpus-catalog.json");
        Files.write(catalog, (Files.readString(catalog, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service("jdbc:postgresql://pgvector:5432/supplypilot_semantic_demo").review(44L, entry))
                .isInstanceOf(IllegalStateException.class).hasMessage("TRUSTED_CATALOG_HASH_MISMATCH");
    }

    private DemoCorpusTrustedApprovalService service(String url) {
        semantic = properties(url);
        return new DemoCorpusTrustedApprovalService(demo, temp.resolve("demo-document-storage").toString(),
                jdbc, documents, chunks, storage, jobs, semantic, policy,
                Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper(), extractor, new TextChunker());
    }

    private static SemanticProperties properties(String url) {
        return new SemanticProperties(url, "user", "password", "embedding-model", 3, "gen-v1",
                true, 10, 0.5, Duration.ofSeconds(2));
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

    private static Path findRepositorySamples() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            Path samples = candidate.resolve("samples/documents");
            if (Files.isRegularFile(samples.resolve("corpus-catalog.json"))) return samples;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository demo corpus is not available to this test");
    }
}

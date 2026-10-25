package com.supplypilot.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.SemanticBackfillService;
import com.supplypilot.document.semantic.SemanticIndexJobRepository;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.rag.DocumentTextExtractor;
import com.supplypilot.document.rag.TextChunker;
import com.supplypilot.document.service.DocumentStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("demo & !prod")
public class DemoCorpusTrustedApprovalService {
    private static final String DATASET_KEY = "SP-901";
    private static final String CORPUS_VERSION = "sp-907-zh-tw-v3";
    private static final String CORPUS_KIND = "REPOSITORY_DEMO_CORPUS";
    private static final String APPROVAL_POLICY = "TRUSTED_DEMO_SYSTEM_APPROVAL_IF_POLICY_ALLOWS";
    private static final String OUTBOUND_POLICY = "EVALUATE_EXISTING_POLICY";

    private final DemoProperties demoProperties;
    private final Path documentStorageRoot;
    private final JdbcTemplate jdbc;
    private final DocumentMetadataRepository documents;
    private final DocumentChunkRepository chunks;
    private final DocumentStorageService storage;
    private final SemanticIndexJobRepository jobs;
    private final SemanticProperties semanticProperties;
    private final OutboundDocumentPolicy outboundPolicy;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final DocumentTextExtractor textExtractor;
    private final TextChunker chunker;

    public DemoCorpusTrustedApprovalService(DemoProperties demoProperties,
            @Value("${supplypilot.document-storage.path:./data/demo-documents}") String documentStoragePath,
            JdbcTemplate jdbc, DocumentMetadataRepository documents, DocumentChunkRepository chunks,
            DocumentStorageService storage, SemanticIndexJobRepository jobs,
            SemanticProperties semanticProperties, OutboundDocumentPolicy outboundPolicy, Clock clock,
            ObjectMapper objectMapper, DocumentTextExtractor textExtractor, TextChunker chunker) {
        this.demoProperties = demoProperties;
        this.documentStorageRoot = Path.of(documentStoragePath).toAbsolutePath().normalize();
        this.jdbc = jdbc;
        this.documents = documents;
        this.chunks = chunks;
        this.storage = storage;
        this.jobs = jobs;
        this.semanticProperties = semanticProperties;
        this.outboundPolicy = outboundPolicy;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.textExtractor = textExtractor;
        this.chunker = chunker;
    }

    @Transactional
    public ApprovalResult review(long documentId, DemoCorpusSyncService.BaselineEntry entry) {
        String semanticTargetFailure = validateTargetAndRepository(entry);
        DocumentMetadata document = documents.findByIdForUpdateWithSupplier(documentId)
                .orElseThrow(() -> new IllegalStateException("TRUSTED_DOCUMENT_MISSING"));
        validateDocument(document, entry);

        String persistedFileHash = sha256CanonicalUtf8(readStoredText(document));
        if (!entry.sourceSha256().equals(persistedFileHash)
                || !entry.sourceSha256().equals(document.getDemoSourceSha256())) {
            throw new IllegalStateException("TRUSTED_SOURCE_HASH_MISMATCH");
        }

        List<DocumentChunk> source = chunks.findByDocumentIdOrderByChunkIndex(documentId);
        if (document.getStatus() != DocumentStatus.READY || source.isEmpty() || !chunksMatchStoredSource(document, source)) {
            throw new IllegalStateException("TRUSTED_CHUNKS_NOT_READY");
        }
        String chunksHash = SemanticBackfillService.sourceHash(source);
        List<String> sourceContents = source.stream().map(DocumentChunk::getContent).toList();
        boolean retainHumanAudit = hasCurrentHumanAudit(document, chunksHash);
        var decision = retainHumanAudit
                ? outboundPolicy.inspect(document, sourceContents, chunksHash)
                : outboundPolicy.inspectContents(sourceContents);
        SemanticReviewStatus policyStatus = SemanticReviewStatus.valueOf(decision.status());
        SemanticReviewStatus status = semanticTargetFailure == null
                ? policyStatus : SemanticReviewStatus.REQUIRES_REVIEW;
        String reason = semanticTargetFailure == null ? decision.reason() : semanticTargetFailure;
        long generation = document.getSemanticIndexGeneration();
        if (!retainHumanAudit) {
            generation = document.reviewTrustedDemoSemanticOutbound(
                    chunksHash, clock.instant(), status, reason);
            documents.save(document);
        }
        boolean queued = status == SemanticReviewStatus.APPROVED && policyStatus == SemanticReviewStatus.APPROVED;
        if (queued) {
            jobs.enqueue(documentId, document.getVersion(), chunksHash, semanticProperties.generationKey(),
                    generation, clock.instant());
        }
        return new ApprovalResult(documentId, document.getVersion(), chunksHash, generation,
                status, reason, queued);
    }

    private static boolean hasCurrentHumanAudit(DocumentMetadata document, String chunksHash) {
        return "USER".equals(document.getSemanticReviewActorType())
                && document.getSemanticApprovedBy() != null
                && document.getSemanticApprovedBy().toString().equals(document.getSemanticReviewActor())
                && "ADMIN_OUTBOUND_REVIEW".equals(document.getSemanticReviewTrigger())
                && document.getSemanticReviewInvalidatedAt() == null
                && document.getSemanticApprovedAt() != null
                && chunksHash.equals(document.getSemanticApprovedSourceHash())
                && document.getVersion().equals(document.getSemanticReviewVersion())
                && document.getSemanticReviewStatus() != null;
    }

    private boolean chunksMatchStoredSource(DocumentMetadata document, List<DocumentChunk> stored) {
        List<com.supplypilot.document.rag.TextChunker.TextChunk> expected;
        try {
            expected = chunker.chunk(textExtractor.extract(document.getStoragePath()));
        } catch (RuntimeException failure) {
            return false;
        }
        if (expected.isEmpty() || expected.size() != stored.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            var expectedChunk = expected.get(i);
            var storedChunk = stored.get(i);
            if (expectedChunk.index() != storedChunk.getChunkIndex()
                    || expectedChunk.startOffset() != storedChunk.getStartOffset()
                    || expectedChunk.endOffset() != storedChunk.getEndOffset()
                    || !expectedChunk.content().equals(storedChunk.getContent())) return false;
        }
        return true;
    }

    private String validateTargetAndRepository(DemoCorpusSyncService.BaselineEntry entry) {
        if (!CORPUS_VERSION.equals(demoProperties.getDatasetVersion()) || entry == null
                || !DATASET_KEY.equals(entry.datasetKey()) || !CORPUS_VERSION.equals(entry.corpusVersion())
                || !CORPUS_KIND.equals(entry.provenanceKind())
                || !"SEED_BASELINE".equals(entry.classification())
                || !APPROVAL_POLICY.equals(entry.approvalPolicy())
                || !OUTBOUND_POLICY.equals(entry.outboundPolicy())) {
            throw new IllegalStateException("TRUSTED_CORPUS_ENVELOPE_REJECTED");
        }

        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        DemoTargetGuard.requirePrimaryTargets(database, demoProperties.getAllowedDatabase(), documentStorageRoot);
        String semanticTargetFailure = semanticTargetFailure();
        Path root = sampleRoot();
        byte[] trustedCatalog = DemoCorpusCatalogTrust.readPinnedCatalog(root);
        validateExactCatalogEntry(trustedCatalog, entry);
        Path source = sourcePath(root, entry.filename());
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IllegalStateException("TRUSTED_REPOSITORY_SOURCE_MISSING");
        }
        String catalogSourceHash = sha256CanonicalUtf8(readUtf8(source));
        if (!entry.sourceSha256().equals(catalogSourceHash)) {
            throw new IllegalStateException("TRUSTED_REPOSITORY_SOURCE_HASH_MISMATCH");
        }
        return semanticTargetFailure;
    }

    private String semanticTargetFailure() {
        String url = semanticProperties.jdbcUrl();
        if (url == null || url.isBlank()) return "SEMANTIC_DEMO_DATABASE_NOT_CONFIGURED";
        String semanticDatabase = databaseName(url);
        String host = databaseHost(url);
        if (!DemoTargetGuard.isExplicitDemoTargetName(demoProperties.getAllowedSemanticDatabase())
                || !demoProperties.getAllowedSemanticDatabase().equals(semanticDatabase)
                || !isAllowedSemanticHost(host)) {
            return "SEMANTIC_DEMO_DATABASE_REJECTED";
        }
        return null;
    }

    private static boolean isAllowedSemanticHost(String host) {
        if (host == null) return false;
        return switch (host.toLowerCase(java.util.Locale.ROOT)) {
            case "pgvector", "localhost", "127.0.0.1", "::1" -> true;
            default -> false;
        };
    }

    private static String databaseHost(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) return null;
        try {
            return URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
        } catch (IllegalArgumentException invalidUrl) {
            return null;
        }
    }

    private void validateExactCatalogEntry(byte[] catalogBytes, DemoCorpusSyncService.BaselineEntry entry) {
        final JsonNode catalog;
        try {
            catalog = objectMapper.readTree(catalogBytes);
        } catch (IOException exception) {
            throw new IllegalStateException("TRUSTED_REPOSITORY_CATALOG_UNREADABLE", exception);
        }
        if (catalog == null || catalog.path("schemaVersion").asInt(-1) != 2
                || !DATASET_KEY.equals(text(catalog, "datasetKey"))
                || !CORPUS_VERSION.equals(text(catalog, "corpusVersion"))
                || !catalog.path("documents").isArray()) {
            throw new IllegalStateException("TRUSTED_REPOSITORY_CATALOG_INVALID");
        }
        JsonNode match = null;
        for (JsonNode row : catalog.path("documents")) {
            if (entry.documentKey().equals(row.path("documentKey").asText())) {
                if (match != null) throw new IllegalStateException("TRUSTED_CATALOG_DUPLICATE_KEY");
                match = row;
            }
        }
        if (match == null || !"SEED_BASELINE".equals(text(match, "classification"))
                || !entry.type().name().equals(text(match, "type"))
                || !entry.title().equals(text(match, "title"))
                || !entry.version().equals(text(match, "version"))
                || !entry.filename().equals(text(match, "filename"))
                || !entry.themeKey().equals(text(match, "themeKey"))
                || !entry.corpusVersion().equals(text(match, "corpusVersion"))
                || !entry.sourceSha256().equalsIgnoreCase(text(match, "sourceSha256"))
                || !entry.approvalPolicy().equals(text(match, "approvalPolicy"))
                || !entry.outboundPolicy().equals(text(match, "outboundPolicy"))
                || !entry.supplierCodes().equals(strings(match, "supplierCodes"))
                || !entry.supplierNames().equals(strings(match, "supplierNames"))
                || !entry.materialCodes().equals(strings(match, "materialCodes"))
                || !entry.materialNames().equals(strings(match, "materialNames"))
                || !entry.fixtureKeys().equals(strings(match, "fixtureKeys"))) {
            throw new IllegalStateException("TRUSTED_CATALOG_ENTRY_MISMATCH");
        }
        JsonNode provenance = match.path("provenance");
        if (!entry.provenanceKind().equals(text(provenance, "kind"))
                || !entry.provenanceDatasetKey().equals(text(provenance, "datasetKey"))
                || !entry.provenanceCorpusVersion().equals(text(provenance, "corpusVersion"))
                || !entry.provenanceOrigin().equals(text(provenance, "origin"))) {
            throw new IllegalStateException("TRUSTED_CATALOG_PROVENANCE_MISMATCH");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("TRUSTED_CATALOG_FIELD_INVALID");
        }
        return value.asText();
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isArray()) throw new IllegalStateException("TRUSTED_CATALOG_ARRAY_INVALID");
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw new IllegalStateException("TRUSTED_CATALOG_ARRAY_INVALID");
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private void validateDocument(DocumentMetadata document, DemoCorpusSyncService.BaselineEntry entry) {
        String supplierCode = document.getSupplier() == null ? null : document.getSupplier().getCode();
        String supplierName = document.getSupplier() == null ? null : document.getSupplier().getName();
        if (!document.hasDemoProvenance(DATASET_KEY, entry.documentKey(), entry.version(),
                    CORPUS_VERSION, entry.sourceSha256(), entry.themeKey(), entry.provenanceOrigin())
                || document.getDocumentType() != entry.type()
                || !entry.title().equals(document.getTitle())
                || !java.util.Objects.equals(entry.supplierCode(), supplierCode)
                || !java.util.Objects.equals(entry.supplierName(), supplierName)) {
            throw new IllegalStateException("TRUSTED_DOCUMENT_PROVENANCE_MISMATCH");
        }
    }

    private String readStoredText(DocumentMetadata document) {
        try (var stream = storage.read(document.getStoragePath())) {
            byte[] bytes = stream.readNBytes(20 * 1024 * 1024 + 1);
            if (bytes.length > 20 * 1024 * 1024) throw new IllegalStateException("TRUSTED_STORED_FILE_TOO_LARGE");
            return decodeUtf8(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("TRUSTED_STORED_FILE_UNAVAILABLE", exception);
        }
    }

    private Path sampleRoot() {
        try {
            Path root = Path.of(demoProperties.getSamplesPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                throw new IllegalStateException("TRUSTED_REPOSITORY_ROOT_INVALID");
            }
            return root.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("TRUSTED_REPOSITORY_ROOT_INVALID", exception);
        }
    }

    private static Path sourcePath(Path root, String filename) {
        if (filename == null || filename.isBlank()) throw new IllegalStateException("TRUSTED_FILENAME_INVALID");
        Path relative = Path.of(filename).normalize();
        if (relative.isAbsolute() || relative.getNameCount() < 1
                || java.util.Arrays.stream(relative.toString().split("[/\\\\]"))
                        .anyMatch(".."::equals)) {
            throw new IllegalStateException("TRUSTED_FILENAME_INVALID");
        }
        Path source = root.resolve(relative.getFileName()).normalize();
        if (!source.startsWith(root) || source.equals(root)) throw new IllegalStateException("TRUSTED_FILENAME_INVALID");
        return source;
    }

    private static String readUtf8(Path source) {
        try {
            if (Files.size(source) > 20L * 1024 * 1024) {
                throw new IllegalStateException("TRUSTED_SOURCE_TOO_LARGE");
            }
            return decodeUtf8(Files.readAllBytes(source));
        } catch (IOException exception) {
            throw new IllegalStateException("TRUSTED_REPOSITORY_SOURCE_UNAVAILABLE", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalStateException("TRUSTED_SOURCE_NOT_UTF8", exception);
        }
    }

    private static String sha256CanonicalUtf8(String text) {
        try {
            byte[] canonical = text.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("TRUSTED_HASH_UNAVAILABLE", exception);
        }
    }

    private static String databaseName(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) return "";
        String withoutQuery = jdbcUrl.split("\\?", 2)[0];
        int slash = withoutQuery.lastIndexOf('/');
        return slash < 0 ? "" : withoutQuery.substring(slash + 1);
    }

    public record ApprovalResult(long documentId, String version, String sourceHash, long generation,
                                 SemanticReviewStatus status, String reason, boolean jobQueued) { }
}


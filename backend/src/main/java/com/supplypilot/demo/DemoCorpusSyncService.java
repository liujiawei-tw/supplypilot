package com.supplypilot.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.dto.response.DocumentSemanticIndexResponse;
import com.supplypilot.document.rag.DocumentIngestionService;
import com.supplypilot.document.rag.DocumentTextExtractor;
import com.supplypilot.document.rag.TextChunker;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.DocumentSemanticLifecycleReader;
import com.supplypilot.document.service.DocumentService;
import com.supplypilot.document.service.DocumentStorageService;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Idempotently synchronizes only rows in the trusted SP-901 repository corpus catalog. */
@Service
@Profile("demo & !prod")
public class DemoCorpusSyncService {
    public static final String DATASET_KEY = "SP-901";
    public static final String CORPUS_VERSION = "sp-907-zh-tw-v3";
    static final String CORPUS_KIND = "REPOSITORY_DEMO_CORPUS";
    static final String APPROVAL_POLICY = "TRUSTED_DEMO_SYSTEM_APPROVAL_IF_POLICY_ALLOWS";
    static final String OUTBOUND_POLICY = "EVALUATE_EXISTING_POLICY";

    private final DemoProperties demoProperties;
    private final Path documentStorageRoot;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final DocumentMetadataRepository documents;
    private final SupplierRepository suppliers;
    private final DocumentService documentService;
    private final DocumentIngestionService ingestion;
    private final DocumentStorageService storage;
    private final DocumentChunkRepository chunks;
    private final DocumentTextExtractor textExtractor;
    private final TextChunker chunker;
    private final DemoCorpusTrustedApprovalService approvals;
    private final DocumentSemanticLifecycleReader lifecycleReader;
    private final DemoCorpusSyncLock synchronizationLock;

    public DemoCorpusSyncService(DemoProperties demoProperties,
            @Value("${supplypilot.document-storage.path:./data/demo-documents}") String documentStoragePath,
            ObjectMapper objectMapper, Clock clock, JdbcTemplate jdbc,
            DocumentMetadataRepository documents, SupplierRepository suppliers,
            DocumentService documentService, DocumentIngestionService ingestion,
            DocumentStorageService storage, DocumentChunkRepository chunks,
            DocumentTextExtractor textExtractor, TextChunker chunker,
            DemoCorpusTrustedApprovalService approvals, DocumentSemanticLifecycleReader lifecycleReader,
            DemoCorpusSyncLock synchronizationLock) {
        this.demoProperties = demoProperties;
        this.documentStorageRoot = Path.of(documentStoragePath).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.jdbc = jdbc;
        this.documents = documents;
        this.suppliers = suppliers;
        this.documentService = documentService;
        this.ingestion = ingestion;
        this.storage = storage;
        this.chunks = chunks;
        this.textExtractor = textExtractor;
        this.chunker = chunker;
        this.approvals = approvals;
        this.lifecycleReader = lifecycleReader;
        this.synchronizationLock = synchronizationLock;
    }

    public synchronized SyncReport synchronizeBaseline() {
        return synchronizationLock.withLock(this::synchronizeBaselineWithLock);
    }

    private SyncReport synchronizeBaselineWithLock() {
        Instant startedAt = clock.instant();
        String runId = UUID.randomUUID().toString();
        requireDemoTarget();
        startRun(startedAt);
        Counters c = new Counters();
        List<DocumentOutcome> outcomes = new ArrayList<>();
        String failureReason = null;
        try {
            Path root = sampleRoot();
            List<BaselineEntry> entries = readCatalog(root);
            c.total = entries.size();
            updateExpectedCount(c.total, clock.instant());
            for (BaselineEntry entry : entries) {
                DocumentOutcome outcome;
                try {
                    outcome = synchronizeEntry(entry, root, c);
                } catch (RuntimeException failure) {
                    c.failures++;
                    outcome = new DocumentOutcome(entry.documentKey(), null, SyncAction.NONE,
                            SyncOutcome.SYNC_FAILED, null, null, null, safeFailureCode(failure));
                }
                outcomes.add(outcome);
                saveOutcome(runId, outcome, clock.instant());
            }
        } catch (RuntimeException failure) {
            c.failures++;
            failureReason = safeFailureCode(failure);
        }
        SyncStatus status = c.failures > 0 ? SyncStatus.FAILED
                : c.conflicts > 0 ? SyncStatus.COMPLETE_WITH_CONFLICTS : SyncStatus.COMPLETE;
        Instant completedAt = clock.instant();
        finishRun(c, status, failureReason, completedAt);
        return new SyncReport(DATASET_KEY, CORPUS_VERSION, runId, status, startedAt, completedAt,
                c.total, c.created, c.adopted, c.unchanged, c.ready, c.approved, c.indexQueued,
                c.pendingIndex, c.policyBlocked, c.conflicts, c.failures, List.copyOf(outcomes));
    }

    private DocumentOutcome synchronizeEntry(BaselineEntry entry, Path root, Counters c) {
        byte[] source = readBounded(sourcePath(root, entry.filename()));
        if (!entry.sourceSha256().equals(sha256CanonicalUtf8(source))) {
            return conflict(entry, null, SyncAction.NONE, "CATALOG_SOURCE_HASH_MISMATCH", c);
        }
        Supplier supplier = resolveSupplier(entry);
        if (entry.supplierCode() != null && supplier == null) {
            return conflict(entry, null, SyncAction.NONE, "SUPPLIER_REFERENCE_MISMATCH", c);
        }

        List<DocumentMetadata> keyed = documents.findDemoBaselineCandidates(
                DATASET_KEY, entry.documentKey(), entry.version());
        DocumentMetadata document;
        SyncAction action;
        if (keyed.size() > 1) return conflict(entry, null, SyncAction.NONE, "DUPLICATE_DEMO_DOCUMENT_KEY", c);
        if (keyed.size() == 1) {
            document = keyed.getFirst();
            if (!matchesCatalogIdentity(document, entry, supplier)
                    || !matchesStoredSource(document, entry.sourceSha256())) {
                return conflict(entry, document.getId(), SyncAction.NONE, "KEYED_BASELINE_CHANGED", c);
            }
            action = SyncAction.UNCHANGED;
            c.unchanged++;
        } else {
            List<DocumentMetadata> candidates = documents.findPossibleLegacyDemoBaselines(
                    entry.type(), entry.title(), entry.version(), supplier == null ? null : supplier.getId());
            if (candidates.size() > 1) {
                return conflict(entry, null, SyncAction.NONE, "AMBIGUOUS_LEGACY_BASELINE_CANDIDATES", c);
            }
            if (candidates.size() == 1) {
                DocumentMetadata legacy = candidates.getFirst();
                if (!matchesCatalogIdentity(legacy, entry, supplier)
                        || !matchesStoredSource(legacy, entry.sourceSha256())) {
                    return conflict(entry, legacy.getId(), SyncAction.NONE, "LEGACY_BASELINE_MISMATCH", c);
                }
                document = adoptLegacy(legacy.getId(), entry);
                action = SyncAction.ADOPTED;
                c.adopted++;
            } else {
                document = createBaseline(entry, supplier, source);
                action = SyncAction.CREATED;
                c.created++;
            }
        }

        if (document.getStatus() == DocumentStatus.INACTIVE) {
            return conflict(entry, document.getId(), action, "BASELINE_DOCUMENT_INACTIVE", c);
        }
        if (!isReadyFromStoredSource(document)) {
            try {
                ingestion.ingestTrustedDemoBaseline(document.getId());
            } catch (RuntimeException failure) {
                c.failures++;
                return new DocumentOutcome(entry.documentKey(), document.getId(), action,
                        SyncOutcome.INGESTION_FAILED, status(document.getId()), reviewStatus(document),
                        indexStatus(document), safeFailureCode(failure));
            }
            document = findForSync(document.getId());
        }
        if (document.getStatus() != DocumentStatus.READY || !isReadyFromStoredSource(document)) {
            c.failures++;
            return new DocumentOutcome(entry.documentKey(), document.getId(), action,
                    SyncOutcome.INGESTION_FAILED, document.getStatus().name(), reviewStatus(document),
                    indexStatus(document), "READY_CHUNKS_SOURCE_MISMATCH");
        }
        c.ready++;

        DemoCorpusTrustedApprovalService.ApprovalResult review;
        try {
            review = approvals.review(document.getId(), entry);
        } catch (RuntimeException failure) {
            c.failures++;
            return new DocumentOutcome(entry.documentKey(), document.getId(), action,
                    SyncOutcome.SYNC_FAILED, document.getStatus().name(), reviewStatus(document),
                    indexStatus(document), safeFailureCode(failure));
        }
        document = findForSync(document.getId());
        DocumentSemanticIndexResponse index = lifecycleReader.read(document);
        if (review.status().name().equals("APPROVED")) c.approved++;
        if (review.jobQueued()) c.indexQueued++;
        if (review.status().name().equals("REQUIRES_REVIEW")) c.policyBlocked++;
        if (review.status().name().equals("APPROVED") && !"INDEXED".equals(index.status())) c.pendingIndex++;
        resolveConflicts(entry.documentKey(), clock.instant());
        SyncOutcome outcome = review.status().name().equals("REQUIRES_REVIEW")
                ? SyncOutcome.POLICY_BLOCKED
                : "INDEXED".equals(index.status()) ? SyncOutcome.INDEXED
                : review.jobQueued() ? SyncOutcome.APPROVED_INDEX_QUEUED
                : "APPROVED".equals(review.status().name()) ? SyncOutcome.APPROVED_INDEX_PENDING : SyncOutcome.READY;
        return new DocumentOutcome(entry.documentKey(), document.getId(), action, outcome,
                document.getStatus().name(), review.status().name(), index.status(), review.reason());
    }

    private DocumentMetadata createBaseline(BaselineEntry entry, Supplier supplier, byte[] source) {
        Long supplierId = supplier == null ? null : supplier.getId();
        Long id = documentService.createTrustedDemoBaseline(entry.type(), supplierId, entry.title(),
                entry.version(), source, entry.datasetKey(), entry.documentKey(), entry.themeKey(),
                entry.corpusVersion(), entry.sourceSha256(), entry.provenanceOrigin()).id();
        return documents.findForSemanticRead(id).orElseThrow();
    }

    private DocumentMetadata adoptLegacy(long id, BaselineEntry entry) {
        DocumentMetadata document = documents.findByIdForUpdateWithSupplier(id).orElseThrow();
        document.adoptDemoBaseline(entry.datasetKey(), entry.documentKey(), entry.themeKey(), entry.corpusVersion(),
                entry.sourceSha256(), entry.provenanceOrigin(), clock.instant());
        return documents.saveAndFlush(document);
    }

    private boolean matchesCatalogIdentity(DocumentMetadata d, BaselineEntry e, Supplier supplier) {
        String code = d.getSupplier() == null ? null : d.getSupplier().getCode();
        String name = d.getSupplier() == null ? null : d.getSupplier().getName();
        boolean provenance = d.isDemoBaseline()
                ? d.hasDemoProvenance(e.datasetKey(), e.documentKey(), e.version(), e.corpusVersion(),
                        e.sourceSha256(), e.themeKey(), e.provenanceOrigin())
                : d.getDemoDatasetKey() == null && d.getDemoDocumentKey() == null && d.getDemoSourceSha256() == null;
        return d.getDocumentType() == e.type() && e.title().equals(d.getTitle())
                && e.version().equals(d.getVersion())
                && java.util.Objects.equals(supplier == null ? null : supplier.getCode(), code)
                && java.util.Objects.equals(e.supplierName(), name) && provenance;
    }

    private boolean matchesStoredSource(DocumentMetadata document, String expectedHash) {
        try (var stream = storage.read(document.getStoragePath())) {
            byte[] bytes = stream.readNBytes(20 * 1024 * 1024 + 1);
            return bytes.length <= 20 * 1024 * 1024 && expectedHash.equals(sha256CanonicalUtf8(bytes));
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private boolean isReadyFromStoredSource(DocumentMetadata document) {
        if (document.getStatus() != DocumentStatus.READY) return false;
        try {
            List<TextChunker.TextChunk> expected = chunker.chunk(textExtractor.extract(document.getStoragePath()));
            List<DocumentChunk> actual = chunks.findByDocumentIdOrderByChunkIndex(document.getId());
            if (expected.isEmpty() || expected.size() != actual.size()) return false;
            for (int i = 0; i < expected.size(); i++) {
                var a = expected.get(i);
                var b = actual.get(i);
                if (a.index() != b.getChunkIndex() || a.startOffset() != b.getStartOffset()
                        || a.endOffset() != b.getEndOffset() || !a.content().equals(b.getContent())) return false;
            }
            return true;
        } catch (RuntimeException failure) { return false; }
    }

    private Supplier resolveSupplier(BaselineEntry entry) {
        if (entry.supplierCode() == null) return null;
        return suppliers.findByCode(entry.supplierCode())
                .filter(s -> entry.supplierName().equals(s.getName())).orElse(null);
    }

    private List<BaselineEntry> readCatalog(Path root) {
        JsonNode catalog;
        try {
            catalog = objectMapper.readTree(DemoCorpusCatalogTrust.readPinnedCatalog(root));
        } catch (IOException exception) {
            throw new IllegalStateException("TRUSTED_CATALOG_UNREADABLE", exception);
        }
        if (catalog == null || catalog.path("schemaVersion").asInt(-1) != 4
                || catalog.path("businessFactApplicabilityVersion").asInt(-1) != 2
                || !DATASET_KEY.equals(text(catalog, "datasetKey"))
                || !CORPUS_VERSION.equals(text(catalog, "corpusVersion"))
                || !catalog.path("documents").isArray()) {
            throw new IllegalStateException("TRUSTED_CATALOG_ENVELOPE_INVALID");
        }
        Set<String> keys = new HashSet<>();
        Set<String> filenames = new HashSet<>();
        List<BaselineEntry> result = new ArrayList<>();
        int manualCount = 0;
        for (JsonNode row : catalog.path("documents")) {
            String classification = text(row, "classification");
            if ("MANUAL_UI_UPLOAD_ONLY".equals(classification)) {
                manualCount++;
                if (row.has("provenance") && !row.get("provenance").isNull()) {
                    throw new IllegalStateException("TRUSTED_MANUAL_ROW_HAS_PROVENANCE");
                }
                continue;
            }
            if (!"SEED_BASELINE".equals(classification)) {
                throw new IllegalStateException("TRUSTED_CATALOG_CLASSIFICATION_INVALID");
            }
            BaselineEntry entry = parseEntry(row);
            if (!keys.add(entry.documentKey()) || !filenames.add(entry.filename())) {
                throw new IllegalStateException("TRUSTED_CATALOG_DUPLICATE_IDENTITY");
            }
            result.add(entry);
        }
        if (manualCount != 1 || result.size() <= 100
                || catalog.path("documentCount").asInt(-1) != result.size() + manualCount
                || catalog.path("seedDocumentCount").asInt(-1) != result.size()
                || catalog.path("manualUiUploadCount").asInt(-1) != manualCount) {
            throw new IllegalStateException("TRUSTED_CATALOG_COUNTS_INVALID");
        }
        return List.copyOf(result);
    }

    private BaselineEntry parseEntry(JsonNode row) {
        String documentKey = text(row, "documentKey");
        String version = text(row, "version");
        String title = text(row, "title");
        String filename = text(row, "filename");
        String corpusVersion = text(row, "corpusVersion");
        String sourceSha256 = text(row, "sourceSha256").toLowerCase(Locale.ROOT);
        String themeKey = text(row, "themeKey");
        String outboundPolicy = text(row, "outboundPolicy");
        String approvalPolicy = text(row, "approvalPolicy");
        List<String> supplierCodes = stringArray(row, "supplierCodes");
        List<String> supplierNames = stringArray(row, "supplierNames");
        List<String> materialCodes = stringArray(row, "materialCodes");
        List<String> materialNames = stringArray(row, "materialNames");
        List<String> fixtureKeys = stringArray(row, "fixtureKeys");
        JsonNode provenance = row.path("provenance");
        String provenanceKind = text(provenance, "kind");
        String provenanceDataset = text(provenance, "datasetKey");
        String provenanceVersion = text(provenance, "corpusVersion");
        String provenanceOrigin = text(provenance, "origin");
        DocumentType type;
        try {
            type = DocumentType.valueOf(text(row, "type"));
        } catch (RuntimeException invalidType) {
            throw new IllegalStateException("TRUSTED_CATALOG_TYPE_INVALID", invalidType);
        }
        if (documentKey.length() > 120 || !documentKey.matches("[A-Z0-9_-]+")
                || title.length() > 255 || filename.isBlank() || version.length() > 40
                || !CORPUS_VERSION.equals(corpusVersion) || !sourceSha256.matches("[0-9a-f]{64}")
                || !themeKey.matches("[a-z0-9_]{1,80}")
                || supplierCodes.size() > 1 || supplierCodes.size() != supplierNames.size()
                || materialCodes.size() != materialNames.size()
                || !CORPUS_KIND.equals(provenanceKind) || !DATASET_KEY.equals(provenanceDataset)
                || !CORPUS_VERSION.equals(provenanceVersion)
                || !("LEGACY_SP906".equals(provenanceOrigin) || "ADDED_SP907".equals(provenanceOrigin))
                || !APPROVAL_POLICY.equals(approvalPolicy) || !OUTBOUND_POLICY.equals(outboundPolicy)) {
            throw new IllegalStateException("TRUSTED_CATALOG_ENTRY_INVALID");
        }
        return new BaselineEntry(DATASET_KEY, documentKey, type, themeKey, version, title, filename,
                corpusVersion, sourceSha256, "SEED_BASELINE", supplierCodes.isEmpty() ? null : supplierCodes.getFirst(),
                supplierNames.isEmpty() ? null : supplierNames.getFirst(), materialCodes, materialNames,
                fixtureKeys, provenanceKind, provenanceDataset, provenanceVersion, provenanceOrigin,
                approvalPolicy, outboundPolicy);
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
        if (relative.isAbsolute() || relative.getNameCount() < 3
                || !"samples".equals(relative.getName(0).toString())
                || !"documents".equals(relative.getName(1).toString())) {
            throw new IllegalStateException("TRUSTED_FILENAME_INVALID");
        }
        for (Path segment : Path.of(filename)) {
            if ("..".equals(segment.toString())) throw new IllegalStateException("TRUSTED_FILENAME_INVALID");
        }
        Path source = root.resolve(relative.getFileName()).normalize();
        if (!source.startsWith(root) || source.equals(root)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IllegalStateException("TRUSTED_REPOSITORY_SOURCE_MISSING");
        }
        return source;
    }

    private static byte[] readBounded(Path path) {
        try {
            if (Files.size(path) > 20L * 1024 * 1024) throw new IllegalStateException("TRUSTED_SOURCE_TOO_LARGE");
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException("TRUSTED_SOURCE_UNAVAILABLE", exception);
        }
    }

    private static String sha256CanonicalUtf8(byte[] bytes) {
        try {
            String source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            byte[] canonical = source.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (java.nio.charset.CharacterCodingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("TRUSTED_SOURCE_HASH_UNAVAILABLE", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("TRUSTED_CATALOG_FIELD_INVALID");
        }
        return value.asText();
    }

    private static List<String> stringArray(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isArray()) throw new IllegalStateException("TRUSTED_CATALOG_ARRAY_INVALID");
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalStateException("TRUSTED_CATALOG_ARRAY_INVALID");
            }
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private void requireDemoTarget() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        DemoTargetGuard.requirePrimaryTargets(database, demoProperties.getAllowedDatabase(), documentStorageRoot);
        if (!CORPUS_VERSION.equals(demoProperties.getDatasetVersion())) {
            throw new IllegalStateException("TRUSTED_DEMO_VERSION_REJECTED");
        }
    }

    private DocumentOutcome conflict(BaselineEntry entry, Long documentId, SyncAction action,
                                     String code, Counters c) {
        c.conflicts++;
        recordConflict(entry.documentKey(), documentId, code, clock.instant());
        return new DocumentOutcome(entry.documentKey(), documentId, action, SyncOutcome.CONFLICT,
                documentId == null ? null : status(documentId), null, null, code);
    }

    private void recordConflict(String key, Long documentId, String code, Instant now) {
        String conflictKey = sha256CanonicalUtf8((DATASET_KEY + "|" + CORPUS_VERSION + "|" + key
                + "|" + (documentId == null ? "" : documentId) + "|" + code).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO demo_corpus_sync_conflicts
                    (conflict_key, dataset_key, corpus_version, document_key, document_id,
                     conflict_code, details, first_seen_at, last_seen_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                ON DUPLICATE KEY UPDATE last_seen_at = VALUES(last_seen_at), resolved_at = NULL
                """, conflictKey, DATASET_KEY, CORPUS_VERSION, key, documentId, code, code,
                Timestamp.from(now), Timestamp.from(now));
    }

    private void resolveConflicts(String key, Instant now) {
        jdbc.update("""
                UPDATE demo_corpus_sync_conflicts SET resolved_at = ?, last_seen_at = ?
                 WHERE dataset_key = ? AND corpus_version = ? AND document_key = ? AND resolved_at IS NULL
                """, Timestamp.from(now), Timestamp.from(now), DATASET_KEY, CORPUS_VERSION, key);
    }

    private void startRun(Instant now) {
        jdbc.update("""
                INSERT INTO demo_corpus_sync_state
                    (dataset_key, corpus_version, status, started_at, updated_at)
                VALUES (?, ?, 'RUNNING', ?, ?)
                ON DUPLICATE KEY UPDATE status = 'RUNNING', expected_documents = 0,
                    created_documents = 0, adopted_legacy_documents = 0, unchanged_documents = 0,
                    ready_documents = 0, approved_documents = 0, requires_review_documents = 0,
                    indexing_queued_documents = 0, conflict_count = 0, failure_reason = NULL,
                    failed_documents = 0, policy_blocked_documents = 0, pending_index_documents = 0,
                    started_at = VALUES(started_at), completed_at = NULL, updated_at = VALUES(updated_at)
                """, DATASET_KEY, CORPUS_VERSION, Timestamp.from(now), Timestamp.from(now));
    }

    private void updateExpectedCount(int total, Instant now) {
        jdbc.update("UPDATE demo_corpus_sync_state SET expected_documents = ?, updated_at = ? "
                + "WHERE dataset_key = ? AND corpus_version = ?", total, Timestamp.from(now),
                DATASET_KEY, CORPUS_VERSION);
    }

    private void saveOutcome(String runId, DocumentOutcome outcome, Instant now) {
        jdbc.update("""
                INSERT INTO demo_corpus_sync_outcomes
                    (run_id, dataset_key, corpus_version, document_key, document_id, outcome,
                     ingestion_state, review_state, index_state, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, DATASET_KEY, CORPUS_VERSION, outcome.documentKey(), outcome.documentId(),
                outcome.outcome().name(), outcome.ingestionState(), outcome.reviewState(), outcome.indexState(),
                outcome.detail(), Timestamp.from(now));
    }

    private void finishRun(Counters c, SyncStatus status, String reason, Instant now) {
        jdbc.update("""
                UPDATE demo_corpus_sync_state SET status = ?, expected_documents = ?, created_documents = ?,
                    adopted_legacy_documents = ?, unchanged_documents = ?, ready_documents = ?,
                    approved_documents = ?, requires_review_documents = ?, indexing_queued_documents = ?,
                    conflict_count = ?, failure_reason = ?, failed_documents = ?, policy_blocked_documents = ?,
                    pending_index_documents = ?, completed_at = ?, updated_at = ?
                 WHERE dataset_key = ? AND corpus_version = ?
                """, status.name(), c.total, c.created, c.adopted, c.unchanged, c.ready, c.approved,
                c.policyBlocked, c.indexQueued, c.conflicts, reason, c.failures, c.policyBlocked,
                c.pendingIndex, Timestamp.from(now), Timestamp.from(now), DATASET_KEY, CORPUS_VERSION);
    }

    private DocumentMetadata findForSync(long id) {
        return documents.findForSemanticRead(id)
                .orElseThrow(() -> new IllegalStateException("SYNC_DOCUMENT_MISSING"));
    }

    private String status(long id) { return findForSync(id).getStatus().name(); }

    private static String reviewStatus(DocumentMetadata document) {
        return document.getSemanticReviewStatus() == null ? null : document.getSemanticReviewStatus().name();
    }

    private String indexStatus(DocumentMetadata document) { return lifecycleReader.read(document).status(); }

    private static String safeFailureCode(RuntimeException failure) {
        String message = failure.getMessage();
        if (message != null && message.matches("[A-Z0-9_]{1,120}")) return message;
        return failure.getClass().getSimpleName();
    }

    public enum SyncStatus { RUNNING, COMPLETE, COMPLETE_WITH_CONFLICTS, FAILED }
    public enum SyncAction { NONE, CREATED, ADOPTED, UNCHANGED }
    public enum SyncOutcome {
        CREATED, ADOPTED, READY, APPROVED_INDEX_QUEUED, APPROVED_INDEX_PENDING, INDEXED,
        POLICY_BLOCKED, CONFLICT, INGESTION_FAILED, SYNC_FAILED
    }

    public record BaselineEntry(String datasetKey, String documentKey, DocumentType type, String themeKey,
            String version, String title, String filename, String corpusVersion, String sourceSha256,
            String classification, String supplierCode, String supplierName, List<String> materialCodes,
            List<String> materialNames, List<String> fixtureKeys, String provenanceKind,
            String provenanceDatasetKey, String provenanceCorpusVersion, String provenanceOrigin,
            String approvalPolicy, String outboundPolicy) {
        public List<String> supplierCodes() { return supplierCode == null ? List.of() : List.of(supplierCode); }
        public List<String> supplierNames() { return supplierName == null ? List.of() : List.of(supplierName); }
    }

    public record DocumentOutcome(String documentKey, Long documentId, SyncAction action, SyncOutcome outcome,
            String ingestionState, String reviewState, String indexState, String detail) { }

    public record SyncReport(String datasetKey, String corpusVersion, String runId, SyncStatus status,
            Instant startedAt, Instant completedAt, int total, int created, int adopted, int unchanged,
            int ready, int approved, int indexQueued, int pendingIndex, int policyBlocked,
            int conflicts, int failures, List<DocumentOutcome> outcomes) { }

    private static final class Counters {
        private int total;
        private int created;
        private int adopted;
        private int unchanged;
        private int ready;
        private int approved;
        private int indexQueued;
        private int pendingIndex;
        private int policyBlocked;
        private int conflicts;
        private int failures;
    }
}

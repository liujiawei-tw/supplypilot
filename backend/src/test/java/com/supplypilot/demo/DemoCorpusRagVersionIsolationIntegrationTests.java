package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.dto.response.DocumentQueryStatus;
import com.supplypilot.document.rag.DocumentIngestionService;
import com.supplypilot.document.rag.DocumentQueryService;
import com.supplypilot.document.rag.DocumentRetrievalService;
import com.supplypilot.document.rag.EmbeddingProvider;
import com.supplypilot.document.rag.RetrievedChunk;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.DocumentSemanticLifecycleReader;
import com.supplypilot.document.semantic.SemanticBackfillService;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.semantic.SemanticRetrievalService;
import com.supplypilot.document.semantic.SemanticStore;
import com.supplypilot.document.service.DocumentService;
import com.supplypilot.document.service.DocumentStorageService;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.testsupport.FixedClockConfiguration;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "spring.config.import=",
        "supplypilot.demo-data-enabled=false",
        "supplypilot.demo.account-password=Demo-Rag-Version-Test-Password!",
        "supplypilot.demo.upgrade-mode=AUTO_SAFE",
        "supplypilot.demo.dataset-version=sp-907-zh-tw-v3",
        "supplypilot.demo.samples-path=../samples/documents",
        "supplypilot.demo.master-data-path=../samples/demo-master-data.json",
        "supplypilot.demo.allowed-database=supplypilot_demo_rag_version_test",
        "supplypilot.demo.allowed-semantic-database=supplypilot_semantic_demo_rag_version_test",
        "supplypilot.document-storage.path=${java.io.tmpdir}/supplypilot-demo-rag-version-documents",
        "supplypilot.semantic.read-enabled=true",
        "supplypilot.semantic.indexing.worker-enabled=false",
        "supplypilot.jwt.secret=dGVzdC1zZWNyZXQtZGVtby1yYWctdmVyc2lvbi10ZXN0LWtleS1hdC1sZWFzdC0yNTYtYml0cw==",
        "supplypilot.frontend-origin=http://localhost:5173"
})
@ActiveProfiles("demo")
@Import({FixedClockConfiguration.class, DemoCorpusRagVersionIsolationIntegrationTests.FakeEmbeddingConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class DemoCorpusRagVersionIsolationIntegrationTests {
    private static final String MYSQL_DATABASE = "supplypilot_demo_rag_version_test";
    private static final String SEMANTIC_DATABASE = "supplypilot_semantic_demo_rag_version_test";
    private static final String DOCUMENT_KEY = "DOC-SUP-001-SUPPLIER-PROFILE-SP907";
    private static final String OLD_CORPUS_VERSION = "sp-906-zh-tw-v2";
    private static final String STORAGE_ROOT = Path.of(System.getProperty("java.io.tmpdir"),
            "supplypilot-demo-rag-version-documents").toString();
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(SEMANTIC_DATABASE);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName(MYSQL_DATABASE)
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void testDatabases(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", MYSQL::getJdbcUrl);
        registry.add("DB_USERNAME", MYSQL::getUsername);
        registry.add("DB_PASSWORD", MYSQL::getPassword);
        registry.add("supplypilot.semantic.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("supplypilot.semantic.username", POSTGRES::getUsername);
        registry.add("supplypilot.semantic.password", POSTGRES::getPassword);
    }

    @Autowired private ObjectMapper objectMapper;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SupplierRepository suppliers;
    @Autowired private MaterialRepository materials;
    @Autowired private SupplierMaterialRepository supplierMaterials;
    @Autowired private DemoDatasetUpgradeService datasetUpgrade;
    @Autowired private UserRepository users;
    @Autowired private DocumentService documentService;
    @Autowired private DocumentIngestionService ingestion;
    @Autowired private DocumentMetadataRepository documents;
    @Autowired private DocumentChunkRepository chunks;
    @Autowired private DocumentStorageService storage;
    @Autowired private EmbeddingProvider lexicalEmbeddings;
    @Autowired private SemanticStore semanticStore;
    @Autowired private SemanticProperties semanticProperties;
    @Autowired private DocumentSemanticLifecycleReader lifecycleReader;
    @Autowired private OutboundDocumentPolicy policy;
    @Autowired private DemoCorpusRagEligibility eligibility;
    @Autowired private DocumentQueryService documentQueryService;
    @Autowired private DemoCorpusSyncService sync;
    @Autowired private DocumentRetrievalService lexicalRetrieval;
    @Autowired private SemanticRetrievalService semanticRetrieval;

    @Test
    @WithMockUser(roles = "PROCUREMENT_STAFF")
    void importingCurrentCatalogRetainsSupersededApprovedVersionButOnlyCurrentAndManualAreRetrievable()
            throws Exception {
        semanticStore.ensureSchema();
        seedAutoSafeRelationalBaseline();
        seedAutoSafeLegacyMarker();
        Supplier supplier = suppliers.findByCode("SUP-001").orElseThrow();

        JsonNode catalog = objectMapper.readTree(Path.of("../samples/documents/corpus-catalog.json").toFile());
        JsonNode currentEntry = findDocument(catalog, DOCUMENT_KEY);
        JsonNode snapshot = objectMapper.readTree(Path.of(
                "../samples/documents/archive/sp907-v1/source-version-snapshot.json").toFile());
        JsonNode previousEntry = findSnapshotEntry(snapshot, DOCUMENT_KEY);
        byte[] oldSource = Files.readAllBytes(Path.of("../samples/documents/archive/sp907-v1",
                previousEntry.path("archivedFilename").asText().substring("archive/sp907-v1/".length())));
        String oldFileHash = canonicalSha256(oldSource);
        assertThat(oldFileHash).isEqualTo(previousEntry.path("previousSourceSha256").asText());

        long oldId = importAndApproveOldBaseline(currentEntry, oldSource, oldFileHash, supplier);
        DocumentMetadata oldBeforeSync = documents.findForSemanticRead(oldId).orElseThrow();
        List<DocumentChunk> oldChunks = chunks.findByDocumentIdOrderByChunkIndex(oldId);
        String oldChunkHash = SemanticBackfillService.sourceHash(oldChunks);
        publish(oldBeforeSync, oldChunks, oldChunkHash, oldChunks.stream().map(DocumentChunk::getContent).toList());
        assertThat(semanticStore.state(oldId, "1.0").status()).isEqualTo("READY");

        ManualFixture manual = createAndApproveManualUpload(supplier);
        assertThat(manual.document().getDemoDatasetKey()).isNull();
        assertThat(Path.of(STORAGE_ROOT, manual.document().getStoragePath())).isRegularFile();

        var relationalUpgrade = datasetUpgrade.autoSafe();
        assertThat(relationalUpgrade.actions()).contains("UPDATE_SUPPLIER:SUP-001");
        assertThat(relationalUpgrade.conflicts()).isEmpty();
        assertThat(suppliers.findByCode("SUP-001").orElseThrow().getName()).isEqualTo("福爾摩沙原料工坊");

        DemoCorpusSyncService.SyncReport firstSync = sync.synchronizeBaseline();
        assertThat(firstSync.status()).isIn(DemoCorpusSyncService.SyncStatus.COMPLETE,
                DemoCorpusSyncService.SyncStatus.COMPLETE_WITH_CONFLICTS);
        assertThat(firstSync.failures()).isZero();
        assertThat(firstSync.outcomes()).filteredOn(outcome -> DOCUMENT_KEY.equals(outcome.documentKey()))
                .singleElement().satisfies(outcome -> {
                    assertThat(outcome.action()).isEqualTo(DemoCorpusSyncService.SyncAction.CREATED);
                    assertThat(outcome.documentId()).isNotEqualTo(oldId);
                });
        long currentId = firstSync.outcomes().stream().filter(outcome -> DOCUMENT_KEY.equals(outcome.documentKey()))
                .findFirst().orElseThrow().documentId();
        DocumentMetadata current = documents.findForSemanticRead(currentId).orElseThrow();
        assertThat(current.getVersion()).isEqualTo("2.0");
        assertThat(eligibility.currentIdentity(current)).isPresent();
        DocumentMetadata mismatchedProvenance = DocumentMetadata.demoBaselineProcessing(
                current.getDocumentType(), current.getSupplier(), current.getTitle(), current.getVersion(),
                "untrusted-source.txt", current.getDemoDatasetKey(), current.getDemoDocumentKey(),
                current.getDemoThemeKey(), current.getDemoCorpusVersion(), "0".repeat(64),
                current.getDemoProvenanceOrigin(), clock.instant());
        assertThat(eligibility.currentIdentity(mismatchedProvenance)).isEmpty();
        assertThat(Files.readAllBytes(Path.of(STORAGE_ROOT, current.getStoragePath())))
                .isEqualTo(Files.readAllBytes(Path.of("../samples/documents/supplier-sup-001-supplier-profile-sp907-v2.txt")));
        List<DocumentChunk> currentChunks = chunks.findByDocumentIdOrderByChunkIndex(currentId);
        String currentChunkHash = SemanticBackfillService.sourceHash(currentChunks);
        assertThat(current.getSemanticReviewStatus()).isEqualTo(SemanticReviewStatus.APPROVED);
        var currentProof = DemoCorpusTrustedApprovalService.VerifiedDemoSource.forCurrentRetrieval(current,
                eligibility.currentIdentity(current).orElseThrow(), currentChunkHash,
                current.getSemanticIndexGeneration()).orElseThrow();
        var currentDecision = policy.inspectVerifiedDemo(current, currentChunks, currentProof);
        assertThat(currentDecision.status()).isEqualTo("APPROVED");
        publish(current, currentChunks, currentChunkHash, currentDecision.safeChunks());

        DemoCorpusSyncService.SyncReport secondSync = sync.synchronizeBaseline();
        assertThat(secondSync.status()).isIn(DemoCorpusSyncService.SyncStatus.COMPLETE,
                DemoCorpusSyncService.SyncStatus.COMPLETE_WITH_CONFLICTS);
        assertThat(secondSync.failures()).isZero();
        assertThat(secondSync.created()).isZero();
        assertThat(secondSync.adopted()).isZero();
        assertThat(secondSync.unchanged()).isEqualTo(115);
        assertThat(documents.findById(oldId)).isPresent();
        assertThat(documents.findById(currentId)).isPresent();
        assertThat(documents.findById(manual.document().getId())).isPresent();
        assertThat(Files.readAllBytes(Path.of(STORAGE_ROOT, manual.document().getStoragePath())))
                .isEqualTo(manual.text().getBytes(StandardCharsets.UTF_8));

        seedAutoSafeCompleteMarker();
        var firstAutoSafe = datasetUpgrade.autoSafe();
        var repeatedAutoSafe = datasetUpgrade.autoSafe();
        assertThat(firstAutoSafe.actions()).isEmpty();
        assertThat(firstAutoSafe.conflicts()).isEmpty();
        assertThat(repeatedAutoSafe.actions()).isEmpty();
        assertThat(repeatedAutoSafe.conflicts()).isEmpty();
        DocumentMetadata oldAfterAutoSafe = documents.findForSemanticRead(oldId).orElseThrow();
        assertThat(oldAfterAutoSafe.getSemanticReviewStatus()).isEqualTo(SemanticReviewStatus.APPROVED);
        assertThat(oldAfterAutoSafe.getSemanticApprovedSourceHash()).isEqualTo(oldChunkHash);
        assertThat(semanticStore.state(oldId, "1.0").status()).isEqualTo("READY");
        assertThat(lifecycleReader.read(oldAfterAutoSafe).status()).isEqualTo("EXCLUDED");

        DocumentMetadata oldAfterSync = documents.findForSemanticRead(oldId).orElseThrow();
        assertThat(oldAfterSync.getVersion()).isEqualTo("1.0");
        assertThat(oldAfterSync.getDemoCorpusVersion()).isEqualTo(OLD_CORPUS_VERSION);
        assertThat(oldAfterSync.getSemanticReviewStatus()).isEqualTo(SemanticReviewStatus.APPROVED);
        assertThat(oldAfterSync.getSemanticReviewVersion()).isEqualTo("1.0");
        assertThat(oldAfterSync.getSemanticApprovedSourceHash()).isEqualTo(oldChunkHash);
        assertThat(oldAfterSync.getSemanticApprovedBy()).isNull();
        assertThat(oldAfterSync.getSemanticReviewActorType()).isEqualTo("SYSTEM");
        assertThat(oldAfterSync.getSemanticReviewActor()).isEqualTo("TRUSTED_DEMO_CORPUS");
        assertThat(oldAfterSync.getSemanticReviewTrigger()).isEqualTo("TRUSTED_DEMO_CORPUS_AUTO_REVIEW");
        assertThat(oldAfterSync.getSemanticIndexGeneration()).isEqualTo(oldBeforeSync.getSemanticIndexGeneration());
        assertThat(eligibility.currentIdentity(oldAfterSync)).isEmpty();
        List<ChunkSnapshot> retainedOldChunks = chunks.findByDocumentIdOrderByChunkIndex(oldId).stream()
                .map(DemoCorpusRagVersionIsolationIntegrationTests::snapshot).toList();
        assertThat(retainedOldChunks).containsExactlyElementsOf(oldChunks.stream()
                .map(DemoCorpusRagVersionIsolationIntegrationTests::snapshot).toList());
        assertThat(Files.readAllBytes(Path.of(STORAGE_ROOT, oldAfterSync.getStoragePath()))).isEqualTo(oldSource);
        assertThat(semanticStore.state(oldId, "1.0").status()).isEqualTo("READY");
        assertThat(semanticStore.matchesSource(oldId, "1.0", oldChunks.stream()
                .map(chunk -> new SemanticStore.SourceChunk(chunk.getChunkIndex(), chunk.getStartOffset(),
                        chunk.getEndOffset(), chunk.getContent())).toList())).isTrue();
        assertThat(semanticStore.state(currentId, "2.0").status()).isEqualTo("READY");
        assertThat(semanticStore.state(manual.document().getId(), "manual-v1").status()).isEqualTo("READY");

        List<Long> lexicalCurrent = lexicalRetrieval.retrieve("主檔對照方式").stream()
                .map(result -> result.chunk().getDocument().getId()).distinct().toList();
        assertThat(lexicalCurrent).contains(currentId).doesNotContain(oldId);
        List<Long> lexicalManual = lexicalRetrieval.retrieve("manual procurement note").stream()
                .map(result -> result.chunk().getDocument().getId()).distinct().toList();
        assertThat(lexicalManual).contains(manual.document().getId());

        List<Long> semanticIds = semanticRetrieval.retrieve("supplier profile retrieval evidence").stream()
                .map(result -> result.chunk().getDocument().getId()).distinct().toList();
        assertThat(semanticIds).contains(currentId, manual.document().getId()).doesNotContain(oldId);
        assertThat(eligibility.isEligible(manual.document())).isTrue();

        DocumentMetadata legacySup002Quote = syncedDocument(firstSync, "DOC-SUP-002-QUOTATION");
        DocumentMetadata currentSup002Quote = syncedDocument(firstSync, "DOC-SUP-002-QUOTATION-SP907");
        List<DocumentChunk> legacyQuoteChunks = chunks.findByDocumentIdOrderByChunkIndex(legacySup002Quote.getId());
        byte[] originalLegacyQuote = Files.readAllBytes(Path.of("../samples/documents/quotation-sup-002-v1.txt"));
        assertThat(Files.readAllBytes(Path.of(STORAGE_ROOT, legacySup002Quote.getStoragePath())))
                .isEqualTo(originalLegacyQuote);
        assertThat(legacyQuoteChunks).isNotEmpty();
        publishApprovedDemoDocument(legacySup002Quote);
        publishApprovedDemoDocument(currentSup002Quote);

        String currentQuoteQuestion = "SUP-002 的 MAT-002 現行報價有效到哪一天？";
        DocumentMetadata supplierContract = syncedDocument(firstSync, "DOC-SUP-002-CONTRACT");
        String supplierContractContent = chunks.findByDocumentIdOrderByChunkIndex(supplierContract.getId())
                .getFirst().getContent();
        assertThat(eligibility.restrictBusinessFactContent(
                supplierContract, currentQuoteQuestion, supplierContractContent)).isEmpty();
        assertThat(eligibility.restrictBusinessFactContent(supplierContract,
                "SUP-002 的 MAT-002 標準交期為何？", supplierContractContent)).isNotBlank();
        List<RetrievedChunk> lexicalCurrentQuote = lexicalRetrieval.retrieve(currentQuoteQuestion).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION).toList();
        assertThat(lexicalCurrentQuote).isNotEmpty().allSatisfy(result -> {
            assertThat(result.chunk().getDocument().getDemoDocumentKey())
                    .isEqualTo("DOC-SUP-002-QUOTATION-SP907");
            assertThat(result.chunk().getDocument().getVersion()).isEqualTo("2.0");
            assertThat(result.chunk().getContent()).contains("2026-11-30").doesNotContain("2026-12-31");
        });
        List<RetrievedChunk> semanticCurrentQuote = semanticRetrieval.retrieve(currentQuoteQuestion).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION).toList();
        assertThat(semanticCurrentQuote).isNotEmpty().allSatisfy(result -> {
            assertThat(result.chunk().getDocument().getDemoDocumentKey())
                    .isEqualTo("DOC-SUP-002-QUOTATION-SP907");
            assertThat(result.chunk().getDocument().getVersion()).isEqualTo("2.0");
            assertThat(result.chunk().getContent()).contains("2026-11-30").doesNotContain("2026-12-31");
        });

        String currentYearQuoteQuestion = "SUP-002 的 MAT-002 目前 2026 年報價有效到哪一天？";
        var currentYearQuoteResponse = documentQueryService.query(currentYearQuoteQuestion);
        assertThat(currentYearQuoteResponse.status()).isEqualTo(DocumentQueryStatus.ANSWERED);
        assertThat(currentYearQuoteResponse.answer()).contains("2026-11-30").doesNotContain("2026-12-31");
        assertThat(currentYearQuoteResponse.sources()).extracting(source -> source.documentKey())
                .contains("DOC-SUP-002-QUOTATION-SP907").doesNotContain("DOC-SUP-002-QUOTATION");

        String currentAtDateQuestion = "SUP-002 的 MAT-002 目前在 2026-10-15 的報價是否有效？";
        assertThat(lexicalRetrieval.retrieve(currentAtDateQuestion).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)
                .toList()).isNotEmpty().allSatisfy(result -> {
            assertThat(result.chunk().getDocument().getDemoDocumentKey()).isEqualTo("DOC-SUP-002-QUOTATION-SP907");
            assertThat(result.chunk().getContent()).contains("【期間 2026-10-15】", "2026-11-30")
                    .doesNotContain("2026-12-31");
        });
        assertThat(semanticRetrieval.retrieve(currentAtDateQuestion).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)
                .toList()).isNotEmpty().allSatisfy(result -> {
            assertThat(result.chunk().getDocument().getDemoDocumentKey()).isEqualTo("DOC-SUP-002-QUOTATION-SP907");
            assertThat(result.chunk().getContent()).contains("【期間 2026-10-15】", "2026-11-30")
                    .doesNotContain("2026-12-31");
        });

        String historicalQuoteQuestion = "SUP-002 在 2025 年的歷史供貨條件有效到何時？";
        List<RetrievedChunk> lexicalHistoricalCandidates = lexicalRetrieval.retrieve(historicalQuoteQuestion);
        List<RetrievedChunk> lexicalHistoricalQuote = lexicalHistoricalCandidates.stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION).toList();
        assertThat(lexicalHistoricalQuote)
                .as("Lexical candidates: %s", lexicalHistoricalCandidates.stream()
                        .map(result -> "%s score=%.3f %s".formatted(
                                result.chunk().getDocument().getDemoDocumentKey(), result.score(),
                                result.chunk().getContent())).toList())
                .isNotEmpty().allSatisfy(result -> {
            assertThat(result.chunk().getDocument().getDemoDocumentKey()).isEqualTo("DOC-SUP-002-QUOTATION");
            assertThat(result.chunk().getDocument().getVersion()).isEqualTo("1.0");
            assertThat(result.chunk().getContent()).contains("【歷史條件】", "2025-06-30")
                    .doesNotContain("2026-12-31", "【現行條件】", "阿薩姆紅茶萃取物（MAT-001",
                            "芒果濃縮汁（MAT-002", "問答：");
        });
        List<RetrievedChunk> semanticHistoricalQuote = semanticRetrieval.retrieve(historicalQuoteQuestion).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION).toList();
        assertThat(semanticHistoricalQuote).isNotEmpty().allSatisfy(result -> {
            assertThat(result.chunk().getDocument().getDemoDocumentKey()).isEqualTo("DOC-SUP-002-QUOTATION");
            assertThat(result.chunk().getDocument().getVersion()).isEqualTo("1.0");
            assertThat(result.chunk().getContent()).contains("【歷史條件】", "2025-06-30")
                    .doesNotContain("2026-12-31", "【現行條件】", "阿薩姆紅茶萃取物（MAT-001",
                            "芒果濃縮汁（MAT-002", "問答：");
        });

        String ambiguousQuoteQuestion = "SUP-002 的 2025 年歷史條件和目前條件分別到哪一天屆滿？";
        assertThat(eligibility.isApplicableToQuery(legacySup002Quote, ambiguousQuoteQuestion)).isTrue();
        assertThat(eligibility.isApplicableToQuery(currentSup002Quote, ambiguousQuoteQuestion)).isTrue();
        List<RetrievedChunk> lexicalComparison = lexicalRetrieval.retrieve(ambiguousQuoteQuestion).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION).toList();
        assertThat(lexicalComparison).extracting(result -> result.chunk().getDocument().getDemoDocumentKey())
                .contains("DOC-SUP-002-QUOTATION", "DOC-SUP-002-QUOTATION-SP907");
        assertThat(lexicalComparison).filteredOn(result -> "DOC-SUP-002-QUOTATION"
                .equals(result.chunk().getDocument().getDemoDocumentKey())).allSatisfy(result ->
                assertThat(result.chunk().getContent()).contains("2025-06-30")
                        .doesNotContain("2026-12-31", "MAT-001").contains("MAT-002"));
        assertThat(lexicalComparison).filteredOn(result -> "DOC-SUP-002-QUOTATION-SP907"
                .equals(result.chunk().getDocument().getDemoDocumentKey())).allSatisfy(result ->
                assertThat(result.chunk().getContent()).contains("2026-11-30").doesNotContain("2026-12-31"));

        List<RetrievedChunk> semanticComparison = semanticRetrieval.retrieve(ambiguousQuoteQuestion).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION).toList();
        assertThat(semanticComparison).extracting(result -> result.chunk().getDocument().getDemoDocumentKey())
                .contains("DOC-SUP-002-QUOTATION", "DOC-SUP-002-QUOTATION-SP907");
        assertThat(semanticComparison).filteredOn(result -> "DOC-SUP-002-QUOTATION"
                .equals(result.chunk().getDocument().getDemoDocumentKey())).allSatisfy(result ->
                assertThat(result.chunk().getContent()).contains("2025-06-30")
                        .doesNotContain("2026-12-31", "MAT-001").contains("MAT-002"));
        assertThat(semanticComparison).filteredOn(result -> "DOC-SUP-002-QUOTATION-SP907"
                .equals(result.chunk().getDocument().getDemoDocumentKey())).allSatisfy(result ->
                assertThat(result.chunk().getContent()).contains("2026-11-30").doesNotContain("2026-12-31"));

        var comparisonResponse = documentQueryService.query(ambiguousQuoteQuestion);
        assertThat(comparisonResponse.status()).isEqualTo(DocumentQueryStatus.ANSWERED);
        assertThat(comparisonResponse.answer()).contains("2025-06-30", "2026-11-30")
                .doesNotContain("2026-12-31");
        assertThat(comparisonResponse.sources()).extracting(source -> source.documentKey())
                .contains("DOC-SUP-002-QUOTATION", "DOC-SUP-002-QUOTATION-SP907");
        assertThat(comparisonResponse.sources()).filteredOn(source ->
                "DOC-SUP-002-QUOTATION".equals(source.documentKey())).allSatisfy(source -> {
            assertThat(source.title()).isNotBlank();
            assertThat(source.version()).isEqualTo("1.0");
            assertThat(source.excerpt()).contains("2025-06-30").doesNotContain("2026-12-31", "MAT-001");
        });
        assertThat(comparisonResponse.sources()).filteredOn(source ->
                "DOC-SUP-002-QUOTATION-SP907".equals(source.documentKey())).allSatisfy(source -> {
            assertThat(source.title()).isNotBlank();
            assertThat(source.version()).isEqualTo("2.0");
            assertThat(source.excerpt()).contains("2026-11-30").doesNotContain("2026-12-31");
        });

        assertThat(eligibility.isApplicableToQuery(legacySup002Quote, currentQuoteQuestion)).isFalse();
        String mat001CurrentQuotation = "SUP-002 的 MAT-001 現行報價有效期限為何？";
        assertThat(eligibility.isApplicableToQuery(legacySup002Quote, mat001CurrentQuotation)).isFalse();
        assertThat(eligibility.isApplicableToQuery(legacySup002Quote,
                "SUP-002 的 MAT-001 目前報價有效期限為何？")).isFalse();
        assertThat(eligibility.isApplicableToQuery(legacySup002Quote,
                "SUP-002 的 MAT-001 V2 報價是否提供 MOQ？")).isFalse();
        assertThat(eligibility.isApplicableToQuery(legacySup002Quote,
                "SUP-002 的 MAT-001 在 2025 年的報價有效期限為何？")).isTrue();
        assertThat(eligibility.isApplicableToQuery(legacySup002Quote,
                "SUP-002 的 MAT-001 在 2025-05-01 的報價條件為何？")).isTrue();
        assertThat(eligibility.isApplicableToQuery(legacySup002Quote,
                "SUP-002 的 MAT-001 以前的報價有效期限為何？")).isFalse();
        assertThat(eligibility.isApplicableToQuery(currentSup002Quote,
                "SUP-002 的 MAT-002 在 2026-10-15 的報價有效期限為何？")).isTrue();
        assertThat(eligibility.isApplicableToQuery(currentSup002Quote,
                "SUP-002 的 MAT-002 在 2026-12-10 的報價有效期限為何？")).isFalse();
        String twoYearComparison = "SUP-002 的 MAT-002 在 2025 和 2026 年的報價有效期限差異為何？";
        assertThat(eligibility.isApplicableToQuery(legacySup002Quote, twoYearComparison)).isTrue();
        assertThat(eligibility.isApplicableToQuery(currentSup002Quote, twoYearComparison)).isTrue();
        List<RetrievedChunk> lexicalTwoYear = lexicalRetrieval.retrieve(twoYearComparison).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION).toList();
        assertThat(lexicalTwoYear).extracting(result -> result.chunk().getDocument().getDemoDocumentKey())
                .contains("DOC-SUP-002-QUOTATION", "DOC-SUP-002-QUOTATION-SP907");
        List<RetrievedChunk> semanticTwoYear = semanticRetrieval.retrieve(twoYearComparison).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION).toList();
        assertThat(semanticTwoYear).extracting(result -> result.chunk().getDocument().getDemoDocumentKey())
                .contains("DOC-SUP-002-QUOTATION", "DOC-SUP-002-QUOTATION-SP907");
        var twoYearResponse = documentQueryService.query(twoYearComparison);
        assertThat(twoYearResponse.status()).isEqualTo(DocumentQueryStatus.ANSWERED);
        assertThat(twoYearResponse.answer()).contains("2025-06-30", "2026-11-30").doesNotContain("2026-12-31");

        String twoDatesWithinHistoricalPeriod =
                "SUP-002 的 MAT-002 在 2025-04-01 和 2025-06-15 的歷史報價條件為何？";
        List<RetrievedChunk> sameSourcePeriods = lexicalRetrieval.retrieve(twoDatesWithinHistoricalPeriod).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION).toList();
        assertThat(sameSourcePeriods).isNotEmpty().allSatisfy(result -> {
            assertThat(result.chunk().getDocument().getDemoDocumentKey()).isEqualTo("DOC-SUP-002-QUOTATION");
            assertThat(result.chunk().getContent()).contains("【期間 2025-04-01】", "【期間 2025-06-15】")
                    .doesNotContain("2026-12-31", "MAT-001");
        });
        assertThat(eligibility.hasCompleteTemporalEvidence(twoDatesWithinHistoricalPeriod,
                sameSourcePeriods.stream().map(result -> result.chunk().getContent()).toList(), true)).isTrue();

        String mismatchedSupplierQuestion = "SUP-001 的 MAT-001 現行報價有效期限為何？";
        assertThat(eligibility.isApplicableToQuery(currentSup002Quote, mismatchedSupplierQuestion)).isFalse();
        assertThat(eligibility.isApplicableToQuery(legacySup002Quote, mismatchedSupplierQuestion)).isFalse();
        String mismatchedMaterialQuestion = "SUP-002 的 MAT-001 現行報價有效期限為何？";
        assertThat(eligibility.isApplicableToQuery(currentSup002Quote, mismatchedMaterialQuestion)).isFalse();
        assertThat(lexicalRetrieval.retrieve(mismatchedSupplierQuestion).stream()
                .filter(result -> "DOC-SUP-002-QUOTATION-SP907"
                        .equals(result.chunk().getDocument().getDemoDocumentKey()))).isEmpty();
        assertThat(semanticRetrieval.retrieve(mismatchedSupplierQuestion).stream()
                .filter(result -> "DOC-SUP-002-QUOTATION-SP907"
                        .equals(result.chunk().getDocument().getDemoDocumentKey()))).isEmpty();
        assertThat(lexicalRetrieval.retrieve(mismatchedMaterialQuestion).stream()
                .filter(result -> "DOC-SUP-002-QUOTATION-SP907"
                        .equals(result.chunk().getDocument().getDemoDocumentKey()))).isEmpty();
        assertThat(semanticRetrieval.retrieve(mismatchedMaterialQuestion).stream()
                .filter(result -> "DOC-SUP-002-QUOTATION-SP907"
                        .equals(result.chunk().getDocument().getDemoDocumentKey()))).isEmpty();

        String latestQuoteQuestion = "SUP-002 的 MAT-002 最新報價有效期限為何？";
        assertThat(eligibility.isApplicableToQuery(legacySup002Quote, latestQuoteQuestion)).isFalse();
        assertThat(eligibility.isApplicableToQuery(currentSup002Quote, latestQuoteQuestion)).isTrue();
        assertThat(lexicalRetrieval.retrieve(latestQuoteQuestion).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)
                .map(result -> result.chunk().getDocument().getDemoDocumentKey()))
                .containsOnly("DOC-SUP-002-QUOTATION-SP907");
        assertThat(semanticRetrieval.retrieve(latestQuoteQuestion).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)
                .map(result -> result.chunk().getDocument().getDemoDocumentKey()))
                .containsOnly("DOC-SUP-002-QUOTATION-SP907");

        String yearOnlyHistory = "SUP-002 的 MAT-002 在 2025 年報價有效期限為何？";
        assertThat(lexicalRetrieval.retrieve(yearOnlyHistory).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)
                .map(result -> result.chunk().getDocument().getDemoDocumentKey()))
                .contains("DOC-SUP-002-QUOTATION");
        assertThat(semanticRetrieval.retrieve(yearOnlyHistory).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)
                .map(result -> result.chunk().getDocument().getDemoDocumentKey()))
                .contains("DOC-SUP-002-QUOTATION");

        String exactHistoricalDate = "SUP-002 的 MAT-002 在 2025-05-01 歷史報價有效期限為何？";
        assertThat(lexicalRetrieval.retrieve(exactHistoricalDate).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)
                .toList()).isNotEmpty().allSatisfy(result -> {
            assertThat(result.chunk().getDocument().getDemoDocumentKey()).isEqualTo("DOC-SUP-002-QUOTATION");
            assertThat(result.chunk().getContent()).contains("【期間 2025-05-01】", "2025-06-30")
                    .doesNotContain("2026-12-31", "MAT-001");
        });
        assertThat(semanticRetrieval.retrieve(exactHistoricalDate).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)
                .toList()).isNotEmpty().allSatisfy(result -> {
            assertThat(result.chunk().getDocument().getDemoDocumentKey()).isEqualTo("DOC-SUP-002-QUOTATION");
            assertThat(result.chunk().getContent()).contains("【期間 2025-05-01】", "2025-06-30")
                    .doesNotContain("2026-12-31", "MAT-001");
        });

        String exactCurrentDate = "SUP-002 的 MAT-002 在 2026-10-15 的報價有效期限為何？";
        assertThat(lexicalRetrieval.retrieve(exactCurrentDate).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)
                .toList()).isNotEmpty().allSatisfy(result -> {
            assertThat(result.chunk().getDocument().getDemoDocumentKey()).isEqualTo("DOC-SUP-002-QUOTATION-SP907");
            assertThat(result.chunk().getContent()).contains("【期間 2026-10-15】", "2026-11-30")
                    .doesNotContain("2026-12-31");
        });
        assertThat(semanticRetrieval.retrieve(exactCurrentDate).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)
                .toList()).isNotEmpty().allSatisfy(result -> {
            assertThat(result.chunk().getDocument().getDemoDocumentKey()).isEqualTo("DOC-SUP-002-QUOTATION-SP907");
            assertThat(result.chunk().getContent()).contains("【期間 2026-10-15】", "2026-11-30")
                    .doesNotContain("2026-12-31");
        });

        String expiredDateQuestion = "SUP-002 的 MAT-002 在 2026-12-10 的報價有效期限為何？";
        assertThat(lexicalRetrieval.retrieve(expiredDateQuestion).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)).isEmpty();
        assertThat(semanticRetrieval.retrieve(expiredDateQuestion).stream()
                .filter(result -> result.chunk().getDocument().getDocumentType() == DocumentType.QUOTATION)).isEmpty();

        var missingMat001Quote = documentQueryService.query(mat001CurrentQuotation);
        assertThat(missingMat001Quote.status()).isEqualTo(DocumentQueryStatus.INSUFFICIENT_EVIDENCE);
        assertThat(missingMat001Quote.sources()).isEmpty();

        String exactMaterialHistory = "SUP-002 的 MAT-002 在 2025 年歷史報價有效到何日？";
        assertThat(lexicalRetrieval.retrieve(exactMaterialHistory).stream()
                .filter(result -> "DOC-SUP-002-QUOTATION".equals(
                        result.chunk().getDocument().getDemoDocumentKey())).toList()).isNotEmpty().allSatisfy(result ->
                assertThat(result.chunk().getContent()).contains("2025-06-30")
                        .doesNotContain("MAT-001", "500 KG", "50 KG", "3 個日曆日", "2026-12-31"));
        assertThat(semanticRetrieval.retrieve(exactMaterialHistory).stream()
                .filter(result -> "DOC-SUP-002-QUOTATION".equals(
                        result.chunk().getDocument().getDemoDocumentKey())).toList()).isNotEmpty().allSatisfy(result ->
                assertThat(result.chunk().getContent()).contains("2025-06-30")
                        .doesNotContain("MAT-001", "500 KG", "50 KG", "3 個日曆日", "2026-12-31"));
        assertThat(eligibility.isApplicableToQuery(manual.document(), currentQuoteQuestion)).isTrue();
        assertThat(eligibility.isApplicableToQuery(current, currentQuoteQuestion)).isTrue();
        assertThat(eligibility.isApplicableToQuery(legacySup002Quote,
                "SUP-002 的歷史供貨條件到期日？")).isFalse();
        assertThat(documents.findById(legacySup002Quote.getId())).isPresent();
        assertThat(chunks.findByDocumentIdOrderByChunkIndex(legacySup002Quote.getId()).stream()
                .map(DemoCorpusRagVersionIsolationIntegrationTests::snapshot).toList())
                .containsExactlyElementsOf(legacyQuoteChunks.stream()
                        .map(DemoCorpusRagVersionIsolationIntegrationTests::snapshot).toList());
        assertThat(semanticStore.state(legacySup002Quote.getId(), "1.0").status()).isEqualTo("READY");

        MockEnvironment productionEnvironment = new MockEnvironment();
        productionEnvironment.setActiveProfiles("demo", "prod");
        DemoCorpusRagEligibility productionEligibility = new DemoCorpusRagEligibility(objectMapper,
                "../samples/documents", STORAGE_ROOT, MYSQL_DATABASE, SEMANTIC_DATABASE,
                semanticProperties.jdbcUrl(), productionEnvironment, jdbc, clock);
        assertThat(productionEligibility.isEligible(current)).isFalse();
        assertThat(productionEligibility.isEligible(manual.document())).isTrue();

        MockEnvironment demoEnvironment = new MockEnvironment();
        demoEnvironment.setActiveProfiles("demo");
        DemoCorpusRagEligibility rejectedSemanticTarget = new DemoCorpusRagEligibility(objectMapper,
                "../samples/documents", STORAGE_ROOT, MYSQL_DATABASE, "supplypilot_semantic_prod",
                semanticProperties.jdbcUrl(), demoEnvironment, jdbc, clock);
        assertThat(rejectedSemanticTarget.isEligible(current)).isFalse();
        DemoCorpusRagEligibility rejectedSemanticHost = new DemoCorpusRagEligibility(objectMapper,
                "../samples/documents", STORAGE_ROOT, MYSQL_DATABASE, SEMANTIC_DATABASE,
                "jdbc:postgresql://untrusted.example:5432/" + SEMANTIC_DATABASE, demoEnvironment, jdbc, clock);
        assertThat(rejectedSemanticHost.isEligible(current)).isFalse();
        DemoCorpusRagEligibility rejectedMysqlTarget = new DemoCorpusRagEligibility(objectMapper,
                "../samples/documents", STORAGE_ROOT, "supplypilot_demo_other_test", SEMANTIC_DATABASE,
                semanticProperties.jdbcUrl(), demoEnvironment, jdbc, clock);
        assertThat(rejectedMysqlTarget.isEligible(current)).isFalse();
    }

    private DocumentMetadata syncedDocument(DemoCorpusSyncService.SyncReport report, String documentKey) {
        Long id = report.outcomes().stream().filter(outcome -> documentKey.equals(outcome.documentKey()))
                .map(DemoCorpusSyncService.DocumentOutcome::documentId).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow(() -> new IllegalStateException("Synced Demo document missing: " + documentKey));
        return documents.findForSemanticRead(id).orElseThrow();
    }

    private void publishApprovedDemoDocument(DocumentMetadata document) {
        List<DocumentChunk> source = chunks.findByDocumentIdOrderByChunkIndex(document.getId());
        String hash = SemanticBackfillService.sourceHash(source);
        var identity = eligibility.currentIdentity(document).orElseThrow();
        var proof = DemoCorpusTrustedApprovalService.VerifiedDemoSource.forCurrentRetrieval(
                document, identity, hash, document.getSemanticIndexGeneration()).orElseThrow();
        var decision = policy.inspectVerifiedDemo(document, source, proof);
        assertThat(decision.status()).isEqualTo("APPROVED");
        publish(document, source, hash, decision.safeChunks());
    }

    private void seedAutoSafeRelationalBaseline() throws IOException {
        JsonNode master = objectMapper.readTree(Path.of("../samples/demo-master-data.json").toFile());
        Map<String, Supplier> suppliersByCode = new LinkedHashMap<>();
        for (JsonNode row : master.path("suppliers")) {
            String code = row.path("code").asText();
            String name = "SUP-001".equals(code) ? "Formosa Ingredients" : row.path("name").asText();
            String country = "SUP-001".equals(code) ? "Taiwan" : row.path("country").asText();
            Supplier supplier = Supplier.create(code, name,
                    country, new BigDecimal(row.path("reliabilityScore").asText()),
                    new BigDecimal(row.path("qualityScore").asText()), clock.instant());
            SupplierStatus status = SupplierStatus.valueOf(row.path("status").asText());
            if (status != SupplierStatus.ACTIVE) supplier.update(null, null, null, null, status, clock.instant());
            suppliersByCode.put(supplier.getCode(), suppliers.saveAndFlush(supplier));
        }

        Map<String, Material> materialsByCode = new LinkedHashMap<>();
        for (JsonNode row : master.path("materials")) {
            Material material = Material.create(row.path("code").asText(), row.path("name").asText(),
                    BaseUnit.valueOf(row.path("unit").asText()), new BigDecimal(row.path("safetyStock").asText()),
                    clock.instant());
            MaterialStatus status = MaterialStatus.valueOf(row.path("status").asText());
            if (status != MaterialStatus.ACTIVE) material.update(null, null, status, clock.instant());
            materialsByCode.put(material.getCode(), materials.saveAndFlush(material));
        }

        for (JsonNode row : master.path("supplierMaterials")) {
            SupplierMaterial link = SupplierMaterial.create(suppliersByCode.get(row.path("supplierCode").asText()),
                    materialsByCode.get(row.path("materialCode").asText()),
                    new BigDecimal(row.path("unitPrice").asText()), new BigDecimal(row.path("moq").asText()),
                    new BigDecimal(row.path("packageMultiple").asText()), row.path("leadTimeDays").asInt(),
                    Currency.valueOf(row.path("currency").asText()), clock.instant());
            SupplierMaterialStatus status = SupplierMaterialStatus.valueOf(row.path("status").asText());
            if (status != SupplierMaterialStatus.ACTIVE) {
                link.update(null, null, null, null, status, clock.instant());
            }
            supplierMaterials.saveAndFlush(link);
        }
        assertThat(suppliersByCode).hasSize(10);
        assertThat(materialsByCode).hasSize(12);
    }

    private void seedAutoSafeCompleteMarker() {
        Timestamp now = Timestamp.from(clock.instant());
        int updated = jdbc.update("""
                UPDATE demo_dataset_state
                SET dataset_version = ?, status = 'COMPLETE', baseline_date = ?,
                    created_at = ?, completed_at = ?, failure_reason = NULL
                WHERE dataset_key = ?
                """, DemoCorpusSyncService.CORPUS_VERSION, java.sql.Date.valueOf(LocalDate.of(2026, 10, 20)),
                now, now, DemoDatasetCoordinator.DATASET_KEY);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO demo_dataset_state
                        (dataset_key, dataset_version, baseline_date, status, created_at, completed_at)
                    VALUES (?, ?, ?, 'COMPLETE', ?, ?)
                    """, DemoDatasetCoordinator.DATASET_KEY, DemoCorpusSyncService.CORPUS_VERSION,
                    java.sql.Date.valueOf(LocalDate.of(2026, 10, 20)), now, now);
        }
    }

    private void seedAutoSafeLegacyMarker() {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO demo_dataset_state
                    (dataset_key, dataset_version, baseline_date, status, created_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETE', ?, ?)
                """, DemoDatasetCoordinator.DATASET_KEY, OLD_CORPUS_VERSION,
                java.sql.Date.valueOf(LocalDate.of(2026, 9, 20)), now, now);
    }

    private static ChunkSnapshot snapshot(DocumentChunk chunk) {
        return new ChunkSnapshot(chunk.getChunkIndex(), chunk.getStartOffset(), chunk.getEndOffset(), chunk.getContent());
    }

    private record ChunkSnapshot(int chunkIndex, int startOffset, int endOffset, String content) {
    }

    private long importAndApproveOldBaseline(JsonNode currentEntry, byte[] source, String sourceHash,
            Supplier supplier) {
        Long id = documentService.createTrustedDemoBaseline(DocumentType.SUPPLIER_PROFILE, supplier.getId(),
                currentEntry.path("title").asText(), "1.0", source, DemoCorpusSyncService.DATASET_KEY,
                DOCUMENT_KEY, currentEntry.path("themeKey").asText(), OLD_CORPUS_VERSION,
                sourceHash, "LEGACY_SP906").id();
        ingestion.ingestTrustedDemoBaseline(id);
        DocumentMetadata old = documents.findById(id).orElseThrow();
        List<DocumentChunk> oldChunks = chunks.findByDocumentIdOrderByChunkIndex(id);
        String hash = SemanticBackfillService.sourceHash(oldChunks);
        old.reviewTrustedDemoSemanticOutbound(hash, clock.instant(), SemanticReviewStatus.APPROVED, null);
        documents.saveAndFlush(old);
        assertThat(old.getStatus()).isEqualTo(DocumentStatus.READY);
        return id;
    }

    private ManualFixture createAndApproveManualUpload(Supplier supplier) throws IOException {
        String text = "Manual procurement note: coffee powder delivery window and receiving checklist.";
        User uploader = users.saveAndFlush(User.create("rag-manual-" + UUID.randomUUID(), "test-hash",
                "Manual RAG Uploader", UserRole.PROCUREMENT_STAFF, clock.instant()));
        MockMultipartFile file = new MockMultipartFile("file", "manual-note.txt", "text/plain",
                text.getBytes(StandardCharsets.UTF_8));
        String storedPath = storage.store(file, ".txt");
        DocumentMetadata manual = DocumentMetadata.processing(DocumentType.PROCUREMENT_SOP, supplier,
                "Manual receiving note", "manual-v1", storedPath, uploader, clock.instant());
        manual.markReady(clock.instant());
        manual = documents.saveAndFlush(manual);
        String lexicalVector = lexicalEmbeddings.embed(text).stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        DocumentChunk chunk = chunks.saveAndFlush(DocumentChunk.create(manual, 0, 0, text.length(), text,
                lexicalVector, clock.instant()));
        String hash = SemanticBackfillService.sourceHash(List.of(chunk));
        manual.reviewSemanticOutbound(hash, uploader.getId(), clock.instant(), SemanticReviewStatus.APPROVED, null);
        manual = documents.saveAndFlush(manual);
        publish(manual, List.of(chunk), hash, List.of(text));
        return new ManualFixture(manual, chunk, text);
    }

    private void publish(DocumentMetadata document, List<DocumentChunk> source, String sourceHash,
                         List<String> safeContent) {
        List<Float> vector = new ArrayList<>(java.util.Collections.nCopies(semanticProperties.dimensions(), 0f));
        vector.set(0, 1f);
        List<SemanticStore.SafeChunk> semanticChunks = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            DocumentChunk chunk = source.get(index);
            semanticChunks.add(new SemanticStore.SafeChunk(chunk.getChunkIndex(), chunk.getStartOffset(),
                    chunk.getEndOffset(), safeContent.get(index), vector));
        }
        assertThat(semanticStore.publish(document.getId(), document.getVersion(), sourceHash,
                document.getSemanticIndexGeneration(), semanticChunks)).isTrue();
    }

    private static JsonNode findDocument(JsonNode catalog, String key) {
        for (JsonNode row : catalog.path("documents")) {
            if (key.equals(row.path("documentKey").asText())) return row;
        }
        throw new IllegalStateException("Current Demo document fixture missing: " + key);
    }

    private static JsonNode findSnapshotEntry(JsonNode snapshot, String key) {
        for (JsonNode row : snapshot.path("sources")) {
            if (key.equals(row.path("documentKey").asText())) return row;
        }
        throw new IllegalStateException("Previous Demo document fixture missing: " + key);
    }

    private static String canonicalSha256(byte[] source) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(source)).toString();
        byte[] canonical = text.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    }

    private record ManualFixture(DocumentMetadata document, DocumentChunk chunk, String text) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeEmbeddingConfiguration {
        @Bean @Primary EmbeddingModel fakeEmbeddingModel() { return new FixedVectorEmbeddingModel(); }
    }

    static class FixedVectorEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            GoogleGenAiTextEmbeddingOptions options = (GoogleGenAiTextEmbeddingOptions) request.getOptions();
            float[] vector = new float[options.getDimensions()];
            vector[0] = 1f;
            return new EmbeddingResponse(List.of(new Embedding(vector, 0)));
        }

        @Override
        public float[] embed(Document document) {
            float[] vector = new float[768];
            vector[0] = 1f;
            return vector;
        }
    }
}

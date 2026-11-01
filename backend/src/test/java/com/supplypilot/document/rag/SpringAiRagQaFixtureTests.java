package com.supplypilot.document.rag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.demo.DemoCorpusRagEligibility;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.dto.response.DocumentSemanticIndexResponse;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.DocumentSemanticLifecycleReader;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.SemanticBackfillService;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.service.DocumentStorageService;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.user.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Deterministic fixture tests at the verified post-retrieval evidence boundary.
 * Curated answerEvidence snippets are mapped into actual corpus files and Spring AI extracted chunks;
 * those chunks enter at the post-processor with catalog versions and exact extracted-text offsets.
 * PgVectorStore integration tests cover physical vector search.
 * This class does not claim to exercise a full Advisor + PostgreSQL retrieval + Gemini E2E path.
 */
@SpringJUnitConfig(SpringAiRagQaFixtureTests.SecurityTestConfiguration.class)
class SpringAiRagQaFixtureTests {
    private static final String FIXTURE_PATH = "../samples/documents/rag-fixtures.json";
    private static final String CORPUS_CATALOG_PATH = "../samples/documents/corpus-catalog.json";
    private static final String CORPUS_VERSION = "sp-907-zh-tw-v3";
    private static final String GENERATION_KEY = "gemini-embedding-001-768-spring-ai-token-v1";
    private static final Pattern SUPPLIER_CODE = Pattern.compile("(?i)SUP-\\d{3}");
    private static final Map<String, CatalogMetadata> CORPUS_METADATA = loadCorpusMetadata();
    private static final Map<String, CatalogSource> CORPUS_SOURCES = new java.util.concurrent.ConcurrentHashMap<>();

    // Keep cross-document cases in the matrix: they verify that all answerEvidence source chunks can
    // be jointly admitted for one multi-fact question, not that any one chunk answers every facet.
    private static final List<String> POSITIVE_FIXTURE_KEYS = List.of(
            "RAG-001", "RAG-002", "RAG-004", "RAG-005", "RAG-006", "RAG-007", "RAG-009", "RAG-010",
            "RAG-011", "RAG-012", "RAG-014", "RAG-015", "RAG-016", "RAG-017", "RAG-018", "RAG-019",
            "RAG-022", "RAG-023", "RAG-026", "RAG-027", "RAG-029", "RAG-031", "RAG-032", "RAG-034");

    @Autowired private SpringAiRagAnswerPipeline answerPipeline;
    @Autowired private DocumentQueryService securedQueryService;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedFixtureCases")
    void fixtureAnswerEvidenceChunksPassLiveSourceValidationAndMatchExpectedCitations(FixtureCase qa) {
        List<SourceSnippet> candidates = qa.answerEvidenceOptions().stream().flatMap(List::stream)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                snippet -> snippet.documentKey() + "\u001f" + snippet.indexedChunk().index(),
                                snippet -> snippet, (first, ignored) -> first, LinkedHashMap::new),
                        selected -> List.copyOf(selected.values())));
        List<VerifiedRagEvidence> verifiedSources = retrieve(qa.question(), candidates);

        assertThat(verifiedSources).as(qa.fixtureKey() + " validated retrieval evidence").isNotEmpty();
        assertThat(verifiedSources).allSatisfy(source -> {
            SourceSnippet indexedSnippet = candidates.stream()
                    .filter(snippet -> snippet.documentKey().equals(source.documentKey())
                            && snippet.startOffset() == source.startOffset()
                            && snippet.endOffset() == source.endOffset())
                    .findFirst().orElseThrow(() -> new AssertionError("Evidence range is not a fixture source"));
            assertThat(source.version()).as(qa.fixtureKey() + " catalog version")
                    .isEqualTo(indexedSnippet.version());
            assertThat(source.documentType()).as(qa.fixtureKey() + " catalog document type")
                    .isEqualTo(indexedSnippet.catalog().documentType());
            int excerptStart = indexedSnippet.fixtureExcerptOffset() - indexedSnippet.startOffset();
            assertThat(indexedSnippet.indexedChunk().content()
                    .substring(excerptStart, excerptStart + indexedSnippet.normalizedExcerpt().length()))
                    .as(qa.fixtureKey() + " fixture excerpt's exact extracted-text offset")
                    .isEqualTo(indexedSnippet.normalizedExcerpt());
            assertThat(source.excerpt()).as(qa.fixtureKey() + " verified source excerpt")
                    .isNotBlank();
        });
        assertThat(verifiedSources.stream().map(VerifiedRagEvidence::documentKey).collect(java.util.stream.Collectors.toSet()))
                .as(qa.fixtureKey() + " expected source documents")
                .containsExactlyInAnyOrderElementsOf(qa.expectedSourceKeys());
        assertThat(verifiedSources).allSatisfy(source -> {
            assertThat(source.version()).as(qa.fixtureKey() + " source version").isNotBlank();
            assertThat(source.relevanceScore()).as(qa.fixtureKey() + " retrieval score").isGreaterThanOrEqualTo(0.45);
        });

        assertThat(qa.expectedStatus()).isEqualTo("ANSWER");
        if (qa.fixtureKey().equals("RAG-018")) {
            VerifiedRagEvidence historicalSource = verifiedSources.stream()
                    .filter(source -> source.documentKey().equals("DOC-SUP-002-QUOTATION"))
                    .findFirst().orElseThrow();
            VerifiedRagEvidence currentSource = verifiedSources.stream()
                    .filter(source -> source.documentKey().equals("DOC-SUP-002-QUOTATION-SP907"))
                    .findFirst().orElseThrow();
            assertThat(historicalSource.version()).isEqualTo("1.0");
            assertThat(currentSource.version()).isEqualTo("2.0");
        }

        if (qa.fixtureKey().equals("RAG-018")) {
            assertThat(verifiedSources).filteredOn(source -> source.documentKey().equals("DOC-SUP-002-QUOTATION"))
                    .extracting(VerifiedRagEvidence::version).containsOnly("1.0");
            assertThat(verifiedSources).filteredOn(source -> source.documentKey()
                    .equals("DOC-SUP-002-QUOTATION-SP907"))
                    .extracting(VerifiedRagEvidence::version).containsOnly("2.0");
        }
    }

    @ParameterizedTest(name = "{0}: insufficient evidence stays unanswered")
    @MethodSource("insufficientFixtureCases")
    void insufficientFixturesHaveNoVerifiedSourceEvidence(FixtureCase qa) {
        assertThat(qa.expectedStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        List<SourceSnippet> candidates = qa.answerEvidenceOptions().stream().flatMap(List::stream).toList();
        assertThat(retrieve(qa.question(), candidates)).as(qa.fixtureKey() + " has no verified context").isEmpty();
    }

    @Test
    void currentSupplierMaterialQuestionRejectsAnotherSuppliersAndMaterialsFixtureSource() {
        FixtureCase current = fixture("RAG-001");
        SourceSnippet wrong = firstSnippet(fixture("RAG-004"));

        List<VerifiedRagEvidence> evidence = retrieve(current.question(), wrong,
                GateState.approved(wrong.version()));

        assertThat(evidence).isEmpty();
    }

    @Test
    void staleVectorVersionCannotAnswerTheCurrentVersionFixtureQuestion() {
        FixtureCase current = fixture("RAG-001");
        SourceSnippet source = lastSnippet(current);

        List<VerifiedRagEvidence> evidence = retrieve(current.question(), source,
                new GateState(DocumentStatus.READY, SemanticReviewStatus.APPROVED, "2.0", "1.0", true, null));

        assertThat(evidence).isEmpty();
    }

    @Test
    void outboundReviewRequiredSourceNeverEntersContext() {
        FixtureCase current = fixture("RAG-001");
        SourceSnippet source = lastSnippet(current);

        List<VerifiedRagEvidence> evidence = retrieve(current.question(), source,
                new GateState(DocumentStatus.READY, SemanticReviewStatus.REQUIRES_REVIEW,
                        source.version(), source.version(), true, null));

        assertThat(evidence).isEmpty();
    }

    @Test
    void nonReadyLiveDocumentCannotSupplyAValidLookingFixtureVector() {
        FixtureCase current = fixture("RAG-001");
        SourceSnippet source = lastSnippet(current);

        assertThat(retrieve(current.question(), source,
                new GateState(DocumentStatus.INACTIVE, SemanticReviewStatus.APPROVED,
                        source.version(), source.version(), true, null))).isEmpty();
    }

    @Test
    void rawOutboundCandidateDoesNotPassWhenApprovedSourceIsMasked() {
        FixtureCase current = fixture("RAG-001");
        String raw = "SUP-001 contact jane@example.com supplies MAT-001; standard delivery is 5 calendar days.";
        SourceSnippet rawSource = new SourceSnippet("DOC-SUP-001-CONTRACT", raw);

        List<VerifiedRagEvidence> evidence = retrieve(current.question(), rawSource,
                new GateState(DocumentStatus.READY, SemanticReviewStatus.APPROVED, "1.0", "1.0", true,
                        "SUP-001 contact [EMAIL] supplies MAT-001; standard delivery is 5 calendar days."));

        assertThat(evidence).isEmpty();
    }

    @Test
    void comparisonRequiresCurrentVerifiedFactsForBothSuppliersAndTheSameMaterial() {
        FixtureCase currentFixture = fixture("RAG-001");
        String supplierQuestion = currentFixture.question();
        List<SourceSnippet> currentCandidates = allSnippets(currentFixture);
        List<SourceSnippet> wrongSupplierCandidates = allSnippets(fixture("RAG-004"));
        List<VerifiedRagEvidence> sources = retrieve(supplierQuestion, currentCandidates);
        assertThat(retrieve(supplierQuestion, wrongSupplierCandidates)).isEmpty();
        assertThat(sources).extracting(VerifiedRagEvidence::supplierCode).containsOnly("SUP-001");
    }

    @ParameterizedTest(name = "query authorization: {0}")
    @MethodSource("queryRoleCases")
    void queryRoleAuthorizationDoesNotInvokeTheSpringAiPipeline(RoleCase roleCase) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "qa-user", "unused", List.of(new SimpleGrantedAuthority("ROLE_" + roleCase.role().name()))));
        reset(answerPipeline);
        when(answerPipeline.answer("fixture question")).thenReturn(
                new SpringAiRagAnswerPipeline.QueryResult(false, null, List.of()));

        if (roleCase.allowed()) {
            assertThat(securedQueryService.query("fixture question").status())
                    .isEqualTo(com.supplypilot.document.dto.response.DocumentQueryStatus.INSUFFICIENT_EVIDENCE);
            verify(answerPipeline).answer("fixture question");
        } else {
            assertThatThrownBy(() -> securedQueryService.query("fixture question"))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(answerPipeline);
        }
    }

    private List<VerifiedRagEvidence> retrieve(String question, SourceSnippet snippet, GateState gate) {
        return retrieve(question, List.of(snippet), Map.of(snippet.documentKey(), gate));
    }

    private List<VerifiedRagEvidence> retrieve(String question, List<SourceSnippet> snippets) {
        Map<String, GateState> gates = new HashMap<>();
        for (SourceSnippet snippet : snippets) {
            gates.putIfAbsent(snippet.documentKey(), GateState.approved(snippet.version()));
        }
        return retrieve(question, snippets, gates);
    }

    private List<VerifiedRagEvidence> retrieve(String question, List<SourceSnippet> snippets,
            Map<String, GateState> gates) {
        Map<String, List<SourceSnippet>> snippetsByDocument = new LinkedHashMap<>();
        for (SourceSnippet snippet : snippets) {
            snippetsByDocument.computeIfAbsent(snippet.documentKey(), ignored -> new ArrayList<>()).add(snippet);
        }
        Map<Long, DocumentMetadata> metadataById = new HashMap<>();
        Map<Long, List<DocumentChunk>> chunksById = new HashMap<>();
        Map<Long, String> hashById = new HashMap<>();
        Map<Long, GateState> gateById = new HashMap<>();
        Map<Long, String> documentKeyById = new HashMap<>();
        Map<Long, List<SourceSnippet>> snippetsById = new HashMap<>();
        for (Map.Entry<String, List<SourceSnippet>> entry : snippetsByDocument.entrySet()) {
            String documentKey = entry.getKey();
            List<SourceSnippet> documentSnippets = entry.getValue().stream()
                    .collect(java.util.stream.Collectors.collectingAndThen(
                            java.util.stream.Collectors.toMap(snippet -> snippet.indexedChunk().index(),
                                    snippet -> snippet, (first, ignored) -> first, LinkedHashMap::new),
                            selected -> List.copyOf(selected.values())));
            CatalogSource corpusSource = documentSnippets.getFirst().catalog();
            long documentId = Integer.toUnsignedLong(documentKey.hashCode()) + 1;
            long documentGeneration = 9L;
            GateState gate = gates.get(documentKey);
            String liveVersion = gate.liveVersion();

            DocumentMetadata document = mock(DocumentMetadata.class);
            when(document.getId()).thenReturn(documentId);
            when(document.getStatus()).thenReturn(gate.status());
            when(document.getVersion()).thenReturn(liveVersion);
            when(document.getSemanticReviewStatus()).thenReturn(gate.reviewStatus());
            when(document.getSemanticReviewVersion()).thenReturn(liveVersion);
            when(document.getSemanticIndexGeneration()).thenReturn(documentGeneration);
            when(document.getDocumentType()).thenReturn(corpusSource.documentType());
            when(document.getTitle()).thenReturn(corpusSource.title());
            when(document.getDemoDocumentKey()).thenReturn(documentKey);
            when(document.isDemoBaseline()).thenReturn(false);

            String supplierCode = supplierCode(documentKey);
            if (supplierCode != null) {
                Supplier supplier = mock(Supplier.class);
                when(supplier.getId()).thenReturn(Long.parseLong(supplierCode.substring("SUP-".length())));
                when(supplier.getCode()).thenReturn(supplierCode);
                when(supplier.getName()).thenReturn(corpusSource.supplierName());
                when(document.getSupplier()).thenReturn(supplier);
            }

            List<DocumentChunk> sourceChunks = new ArrayList<>();
            for (SpringAiDocumentProcessingService.SourceChunk indexedChunk : corpusSource.chunks()) {
                DocumentChunk source = mock(DocumentChunk.class);
                when(source.getChunkIndex()).thenReturn(indexedChunk.index());
                when(source.getStartOffset()).thenReturn(indexedChunk.startOffset());
                when(source.getEndOffset()).thenReturn(indexedChunk.endOffset());
                when(source.getContent()).thenReturn(indexedChunk.content());
                sourceChunks.add(source);
            }
            String sourceHash = SemanticBackfillService.sourceHash(sourceChunks);
            when(document.getSemanticApprovedSourceHash()).thenReturn(sourceHash);

            metadataById.put(documentId, document);
            chunksById.put(documentId, List.copyOf(sourceChunks));
            hashById.put(documentId, sourceHash);
            gateById.put(documentId, gate);
            documentKeyById.put(documentId, documentKey);
            snippetsById.put(documentId, documentSnippets);
        }

        DocumentMetadataRepository documents = mock(DocumentMetadataRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        DocumentSemanticLifecycleReader lifecycle = mock(DocumentSemanticLifecycleReader.class);
        OutboundDocumentPolicy policy = mock(OutboundDocumentPolicy.class);
        DemoCorpusRagEligibility demoEligibility = mock(DemoCorpusRagEligibility.class);
        List<Document> candidates = new ArrayList<>();
        for (Map.Entry<Long, DocumentMetadata> entry : metadataById.entrySet()) {
            long documentId = entry.getKey();
            DocumentMetadata document = entry.getValue();
            List<DocumentChunk> sourceChunks = chunksById.get(documentId);
            String sourceHash = hashById.get(documentId);
            GateState gate = gateById.get(documentId);
            String documentKey = documentKeyById.get(documentId);
            List<SourceSnippet> documentSnippets = snippetsById.get(documentId);
            CatalogSource corpusSource = documentSnippets.getFirst().catalog();
            long documentGeneration = 9L;

            when(documents.findForSemanticRead(documentId)).thenReturn(java.util.Optional.of(document));
            when(chunks.findByDocumentIdOrderByChunkIndex(documentId)).thenReturn(sourceChunks);
            when(lifecycle.read(document)).thenReturn(new DocumentSemanticIndexResponse(
                    "INDEXED", 41L, sourceHash, sourceChunks.size(), sourceChunks.size(), null,
                    GENERATION_KEY, documentGeneration));
            when(demoEligibility.isEligible(document)).thenReturn(gate.eligible());
            List<String> safeChunks = corpusSource.chunks().stream()
                    .map(chunk -> gate.approvedChunk() == null ? chunk.content() : gate.approvedChunk()).toList();
            when(demoEligibility.inspectSource(document, sourceChunks, sourceHash, documentGeneration, policy))
                    .thenReturn(new OutboundDocumentPolicy.Decision("APPROVED", null, safeChunks));
            when(demoEligibility.restrictBusinessFactContent(eq(document), eq(question),
                    org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(2));
            for (SourceSnippet snippet : documentSnippets) {
                when(demoEligibility.restrictBusinessFactContent(document, question,
                        snippet.indexedChunk().content())).thenReturn(snippet.indexedChunk().content());
            }

            for (SourceSnippet snippet : documentSnippets) {
                SpringAiDocumentProcessingService.SourceChunk indexedChunk = snippet.indexedChunk();
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("documentId", documentId);
                metadata.put("documentVersion", gate.vectorVersion());
                metadata.put("generationKey", GENERATION_KEY);
                metadata.put("sourceHash", sourceHash);
                metadata.put("documentGeneration", documentGeneration);
                metadata.put("chunkIndex", indexedChunk.index());
                metadata.put("startOffset", indexedChunk.startOffset());
                metadata.put("endOffset", indexedChunk.endOffset());
                metadata.put("documentStatus", DocumentStatus.READY.name());
                metadata.put("outboundApprovalStatus", SemanticReviewStatus.APPROVED.name());
                metadata.put("documentKey", documentKey);
                metadata.put("documentType", corpusSource.documentType().name());
                metadata.put("title", corpusSource.title());
                candidates.add(Document.builder().id(UUID.nameUUIDFromBytes(
                                (documentKey + "\u001f" + indexedChunk.index() + "\u001f" + indexedChunk.content())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString()).text(indexedChunk.content()).metadata(metadata).score(0.93).build());
            }
        }
        when(demoEligibility.hasCompleteTemporalEvidence(eq(question), anyList(), eq(false))).thenReturn(true);

        SemanticProperties properties = new SemanticProperties("jdbc:postgresql://unused/test", "test", "test",
                "gemini-embedding-001", 768, GENERATION_KEY, true, 8, 0.45, Duration.ofSeconds(2));
        RagEvidenceCollector collector = new RagEvidenceCollector();
        RagDocumentPostProcessor postProcessor = new RagDocumentPostProcessor(documents, chunks, lifecycle, policy,
                demoEligibility, properties, collector);
        collector.begin();
        try {
            postProcessor.process(new Query(question), candidates);
            return collector.get();
        } catch (NoVerifiedRagEvidenceException noEvidence) {
            return List.of();
        } finally {
            collector.clear();
        }
    }

    private static Stream<Arguments> supportedFixtureCases() {
        return fixtureCases(POSITIVE_FIXTURE_KEYS).map(Arguments::of);
    }

    private static Stream<Arguments> insufficientFixtureCases() {
        return fixtureCases(List.of("RAG-003", "RAG-008", "RAG-024", "RAG-035", "RAG-045", "RAG-055",
                "RAG-065", "RAG-075", "RAG-085", "RAG-095", "RAG-105", "RAG-115", "RAG-125", "RAG-126"))
                .map(Arguments::of);
    }

    private static Stream<Arguments> queryRoleCases() {
        return Stream.of(
                Arguments.of(new RoleCase(UserRole.PROCUREMENT_STAFF, true)),
                Arguments.of(new RoleCase(UserRole.PROCUREMENT_MANAGER, true)),
                Arguments.of(new RoleCase(UserRole.ADMIN, true)),
                Arguments.of(new RoleCase(UserRole.PRODUCTION_PLANNER, false)),
                Arguments.of(new RoleCase(UserRole.WAREHOUSE_STAFF, false)));
    }

    private static Stream<FixtureCase> fixtureCases(List<String> selectedKeys) {
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(Path.of(FIXTURE_PATH)));
            if (root.path("schemaVersion").asInt() != 2 || !CORPUS_VERSION.equals(root.path("corpusVersion").asText())) {
                throw new IllegalStateException("Unexpected RAG fixture schema or corpus version");
            }
            Map<String, JsonNode> byKey = new HashMap<>();
            root.path("fixtures").forEach(node -> byKey.put(node.path("fixtureKey").asText(), node));
            List<FixtureCase> result = new ArrayList<>();
            for (String key : selectedKeys) {
                JsonNode node = byKey.get(key);
                if (node == null) throw new IllegalStateException("Missing RAG fixture " + key);
                Set<String> sources = new LinkedHashSet<>(textValues(node.path("expectedSourceKeys")));
                List<List<SourceSnippet>> evidenceOptions = fixtureSnippets(node.path("answerEvidence"));
                result.add(new FixtureCase(key, node.path("category").asText(), node.path("question").asText(),
                        node.path("expectedStatus").asText(), sources, evidenceOptions));
            }
            return result.stream();
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read " + FIXTURE_PATH, exception);
        }
    }

    private static List<List<SourceSnippet>> fixtureSnippets(JsonNode evidence) {
        List<List<SourceSnippet>> options = new ArrayList<>();
        if (evidence != null && evidence.isArray()) {
            for (JsonNode option : evidence) {
                JsonNode sources = option.path("sources");
                if (!sources.isArray()) continue;
                Map<String, SourceSnippet> distinct = new LinkedHashMap<>();
                for (JsonNode source : sources) {
                    String documentKey = source.path("documentKey").asText();
                    String excerpt = source.path("excerpt").asText();
                    if (documentKey.isBlank() || excerpt.isBlank()) continue;
                    CatalogSource entry = catalogSource(documentKey);
                    int rawEvidenceOffset = entry.rawText().indexOf(excerpt);
                    if (rawEvidenceOffset < 0) {
                        throw new IllegalStateException("Fixture excerpt is not present in corpus source: "
                                + documentKey);
                    }
                    String normalizedExcerpt = normalizeFixtureExcerpt(excerpt);
                    int evidenceOffset = entry.extractedText().indexOf(normalizedExcerpt);
                    if (evidenceOffset < 0) {
                        throw new IllegalStateException("Normalized fixture excerpt is not present in extracted corpus source: "
                                + documentKey);
                    }
                    int evidenceEnd = evidenceOffset + normalizedExcerpt.length();
                    int coveredUntil = evidenceOffset;
                    for (SpringAiDocumentProcessingService.SourceChunk indexedChunk : entry.chunks()) {
                        int mappedStart = Math.max(evidenceOffset, indexedChunk.startOffset());
                        int mappedEnd = Math.min(evidenceEnd, indexedChunk.endOffset());
                        if (mappedStart >= mappedEnd) continue;
                        if (mappedStart > coveredUntil) {
                            throw new IllegalStateException("Fixture excerpt has a gap in indexed chunks: "
                                    + documentKey);
                        }
                        String indexedExcerpt = normalizedExcerpt.substring(
                                mappedStart - evidenceOffset, mappedEnd - evidenceOffset);
                        int chunkLocalStart = mappedStart - indexedChunk.startOffset();
                        if (!indexedChunk.content().regionMatches(chunkLocalStart, indexedExcerpt, 0,
                                indexedExcerpt.length())) {
                            throw new IllegalStateException("Fixture excerpt does not match indexed chunk offsets: "
                                    + documentKey);
                        }
                        SourceSnippet snippet = new SourceSnippet(documentKey, excerpt, indexedExcerpt, entry,
                                indexedChunk, mappedStart);
                        distinct.putIfAbsent(documentKey + "\u001f" + indexedChunk.index(), snippet);
                        coveredUntil = Math.max(coveredUntil, mappedEnd);
                    }
                    if (coveredUntil < evidenceEnd) {
                        throw new IllegalStateException("Fixture excerpt is not fully covered by indexed chunks: "
                                + documentKey);
                    }
                }
                if (!distinct.isEmpty()) options.add(List.copyOf(distinct.values()));
            }
        }
        return List.copyOf(options);
    }

    private static Map<String, CatalogMetadata> loadCorpusMetadata() {
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(Path.of(CORPUS_CATALOG_PATH)));
            if (!CORPUS_VERSION.equals(root.path("corpusVersion").asText())) {
                throw new IllegalStateException("Unexpected corpus catalog version");
            }
            Path repositoryRoot = Path.of("..").toAbsolutePath().normalize();
            Map<String, CatalogMetadata> sources = new HashMap<>();
            for (JsonNode document : root.path("documents")) {
                String key = document.path("documentKey").asText();
                String filename = document.path("filename").asText();
                Path sourcePath = repositoryRoot.resolve(filename).normalize();
                if (!sourcePath.startsWith(repositoryRoot)) {
                    throw new IllegalStateException("Corpus source escaped repository root: " + filename);
                }
                String supplierName = document.path("supplierNames").isArray()
                        && document.path("supplierNames").size() > 0
                        ? document.path("supplierNames").get(0).asText() : "";
                sources.put(key, new CatalogMetadata(document.path("version").asText(), filename, sourcePath,
                        document.path("title").asText(), DocumentType.valueOf(document.path("type").asText()),
                        supplierName));
            }
            return Map.copyOf(sources);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read " + CORPUS_CATALOG_PATH, exception);
        }
    }

    private static CatalogSource catalogSource(String documentKey) {
        return CORPUS_SOURCES.computeIfAbsent(documentKey, key -> {
            CatalogMetadata metadata = CORPUS_METADATA.get(key);
            if (metadata == null) throw new IllegalStateException("Missing corpus catalog key " + key);
            try {
                byte[] bytes = Files.readAllBytes(metadata.sourcePath());
                String rawText = new String(bytes, StandardCharsets.UTF_8);
                DocumentStorageService storage = mock(DocumentStorageService.class);
                when(storage.read(metadata.filename())).thenReturn(new ByteArrayInputStream(bytes));
                SpringAiDocumentProcessingService processor = new SpringAiDocumentProcessingService(storage,
                        new RagChunkingProperties(800, 80, 1_000));
                try {
                    String extractedText = processor.extract(metadata.filename());
                    List<SpringAiDocumentProcessingService.SourceChunk> chunks = processor.split(extractedText);
                    return new CatalogSource(metadata.version(), metadata.filename(), rawText, extractedText, chunks,
                            metadata.title(), metadata.documentType(), metadata.supplierName());
                } finally {
                    processor.closeParserExecutor();
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to read corpus source " + metadata.filename(), exception);
            }
        });
    }

    private static String normalizeFixtureExcerpt(String text) {
        return text.replace('\u0000', ' ').replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n+ *", "\n").trim();
    }

    private static List<String> textValues(JsonNode values) {
        if (values == null || !values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> { if (value.isTextual()) result.add(value.asText()); });
        return List.copyOf(result);
    }

    private static FixtureCase fixture(String key) {
        return fixtureCases(List.of(key)).findFirst().orElseThrow();
    }

    private static SourceSnippet firstSnippet(FixtureCase qa) {
        return qa.answerEvidenceOptions().stream().flatMap(List::stream).findFirst().orElseThrow();
    }

    private static List<SourceSnippet> allSnippets(FixtureCase qa) {
        return qa.answerEvidenceOptions().stream().flatMap(List::stream)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                snippet -> snippet.documentKey() + "\u001f" + snippet.indexedChunk().index(),
                                snippet -> snippet, (first, ignored) -> first, LinkedHashMap::new),
                        selected -> List.copyOf(selected.values())));
    }

    private static SourceSnippet lastSnippet(FixtureCase qa) {
        return qa.answerEvidenceOptions().stream().flatMap(List::stream)
                .filter(snippet -> !new EvidenceValidator()
                        .supportedExcerpt(qa.question(), snippet.indexedChunk().content(),
                                snippet.catalog().extractedText()).isBlank())
                .reduce((first, last) -> last)
                .orElseThrow(() -> new IllegalStateException(
                        "No indexed answerEvidence chunk supports fixture question " + qa.fixtureKey()));
    }

    private static String supplierCode(String documentKey) {
        Matcher matcher = SUPPLIER_CODE.matcher(documentKey);
        return matcher.find() ? matcher.group().toUpperCase(java.util.Locale.ROOT) : null;
    }

    private record FixtureCase(String fixtureKey, String category, String question, String expectedStatus,
                              Set<String> expectedSourceKeys,
                              List<List<SourceSnippet>> answerEvidenceOptions) {
        @Override public String toString() { return fixtureKey + " [" + category + "]"; }
    }

    private record SourceSnippet(String documentKey, String excerpt, String normalizedExcerpt, CatalogSource catalog,
                                 SpringAiDocumentProcessingService.SourceChunk indexedChunk,
                                 int fixtureExcerptOffset) {
        private String version() { return catalog.version(); }
        private int startOffset() { return indexedChunk.startOffset(); }
        private int endOffset() { return indexedChunk.endOffset(); }
        private SourceSnippet(String documentKey, String excerpt) {
            this(documentKey, excerpt, excerpt, syntheticCatalog(excerpt), 0);
        }
        private SourceSnippet(String documentKey, String excerpt, String normalizedExcerpt, CatalogSource catalog,
                int fixtureExcerptOffset) {
            this(documentKey, excerpt, normalizedExcerpt, catalog, catalog.chunks().getFirst(), fixtureExcerptOffset);
        }
        private static CatalogSource syntheticCatalog(String excerpt) {
            return new CatalogSource("1.0", "negative-test-only", excerpt, excerpt,
                    List.of(new SpringAiDocumentProcessingService.SourceChunk(0, 0, excerpt.length(), excerpt)),
                    "negative test", DocumentType.SUPPLIER_CONTRACT, "SUP-001");
        }
    }
    private record CatalogMetadata(String version, String filename, Path sourcePath, String title,
                                   DocumentType documentType, String supplierName) { }
    private record CatalogSource(String version, String filename, String rawText, String extractedText,
                                 List<SpringAiDocumentProcessingService.SourceChunk> chunks, String title,
                                 DocumentType documentType, String supplierName) { }
    private record GateState(DocumentStatus status, SemanticReviewStatus reviewStatus, String liveVersion,
                             String vectorVersion, boolean eligible, String approvedChunk) {
        private static GateState approved(String version) {
            return new GateState(DocumentStatus.READY, SemanticReviewStatus.APPROVED,
                    version, version, true, null);
        }
    }
    private record RoleCase(UserRole role, boolean allowed) {
        @Override public String toString() { return role.name() + " allowed=" + allowed; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import(SecurityTestBeans.class)
    static class SecurityTestConfiguration { }

    @Configuration(proxyBeanMethods = false)
    static class SecurityTestBeans {
        @Bean @Primary
        SpringAiRagAnswerPipeline qaPipeline() { return mock(SpringAiRagAnswerPipeline.class); }

        @Bean
        DocumentQueryService qaDocumentQueryService(SpringAiRagAnswerPipeline pipeline) {
            return new DocumentQueryService(pipeline, new OutboundDocumentPolicy());
        }
    }
}

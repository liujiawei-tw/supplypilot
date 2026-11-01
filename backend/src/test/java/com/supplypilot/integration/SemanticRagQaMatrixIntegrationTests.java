package com.supplypilot.integration;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.SemanticBackfillService;
import com.supplypilot.document.semantic.SemanticIndexJobRepository;
import com.supplypilot.document.semantic.SemanticIndexJobStatus;
import com.supplypilot.document.semantic.SemanticIndexWorker;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.semantic.SemanticStore;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Deterministic API-level RAG QA matrix. Each case writes a vector through the application
 * PgVectorStore, queries through the configured Spring AI Advisor, and checks the HTTP response.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.ai.model.embedding.text=google-genai",
        "spring.ai.google.genai.api-key=rag-qa-test-key",
        "supplypilot.semantic.indexing.worker-enabled=false"
})
@AutoConfigureMockMvc
@Import(SemanticRagQaMatrixIntegrationTests.FakeConfiguration.class)
class SemanticRagQaMatrixIntegrationTests extends AbstractIntegrationTest {
    private static final List<String> FIXTURE_POSITIVE_KEYS = List.of(
            "RAG-005", "RAG-007", "RAG-009", "RAG-010", "RAG-014", "RAG-015", "RAG-018", "RAG-022",
            "RAG-026", "RAG-027", "RAG-029");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Container
    static final PostgreSQLContainer<?> CONTAINER = POSTGRES;

    @DynamicPropertySource
    static void semanticProperties(DynamicPropertyRegistry registry) {
        registry.add("supplypilot.semantic.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("supplypilot.semantic.username", POSTGRES::getUsername);
        registry.add("supplypilot.semantic.password", POSTGRES::getPassword);
        registry.add("supplypilot.semantic.read-enabled", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @Autowired SupplierRepository suppliers;
    @Autowired DocumentMetadataRepository documents;
    @Autowired DocumentChunkRepository chunks;
    @Autowired JwtTokenService tokens;
    @Autowired Clock clock;
    @Autowired SemanticProperties semanticProperties;
    @Autowired SemanticStore semanticStore;
    @Autowired SemanticIndexWorker worker;
    @Autowired SemanticIndexJobRepository jobs;
    @Autowired FakeEmbeddingModel embeddings;
    @Autowired FakeChatModel chat;
    @Autowired JdbcTemplate mysql;
    @Autowired @Qualifier("ragWriteVectorStore") PgVectorStore writeVectorStore;
    @Autowired @Qualifier("ragQueryVectorStore") PgVectorStore queryVectorStore;
    private JdbcTemplate semanticJdbc;

    @BeforeEach
    void clearDatabasesAndFakeProviders() throws SQLException {
        DriverManagerDataSource semanticDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        semanticDataSource.setDriverClassName("org.postgresql.Driver");
        semanticJdbc = new JdbcTemplate(semanticDataSource);

        embeddings.reset();
        chat.reset();
        mysql.update("DELETE FROM semantic_index_jobs");
        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
        semanticStore.ensureSchema();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM semantic_chunks");
            statement.executeUpdate("DELETE FROM " + SemanticStore.RAG_VECTOR_TABLE);
            statement.executeUpdate("DELETE FROM semantic_document_indexes");
            statement.executeUpdate("DELETE FROM semantic_gate_audit");
        }
    }

    @ParameterizedTest(name = "{0}: Advisor returns a supported answer with live source citation")
    @MethodSource("answerableCases")
    void advisorReturnsSupportedAnswerAndSource(QaCase qa) throws Exception {
        User admin = user(UserRole.ADMIN);
        long documentId = createReadyDocument(admin, qa.key(), qa.content());
        approve(admin, documentId);
        processQueuedIndex(documentId);

        String retrievalAssertion = qa.key().equals("RAG-PG-PII-01")
                ? "Forklift capacity is 500 kilograms." : qa.content();
        assertRetrievedFromPgVector(qa.question(), retrievalAssertion);
        chat.answerOverride.set(qa.expectedAnswer());
        int previousChatCalls = chat.calls.get();
        JsonNode response = response(query(admin, qa.question()));

        assertThat(response.path("status").asText()).isEqualTo("ANSWERED");
        assertThat(response.path("answer").asText()).contains(qa.expectedAnswer());
        JsonNode sources = response.path("sources");
        assertThat(sources).hasSize(1);
        JsonNode source = sources.get(0);
        assertThat(source.path("documentId").asLong()).isEqualTo(documentId);
        assertThat(source.path("title").asText()).isEqualTo(qa.key());
        assertThat(source.path("version").asText()).isEqualTo("v1");
        assertThat(source.path("startOffset").asInt()).isZero();
        assertThat(source.path("endOffset").asInt()).isEqualTo(qa.content().length());
        assertThat(source.path("excerpt").asText()).contains(qa.expectedAnswer());
        assertThat(source.path("relevanceScore").asDouble()).isGreaterThanOrEqualTo(0.45);
        assertThat(chat.calls.get()).isEqualTo(previousChatCalls + 1);
        if (qa.key().equals("RAG-PG-PII-01")) {
            assertThat(chat.promptContents.getLast())
                    .contains("Forklift capacity is 500 kilograms. Contact [EMAIL]")
                    .doesNotContain("alice@example.com");
        } else {
            assertThat(chat.promptContents.getLast()).contains(qa.content());
        }
        assertThat(embeddings.tasks).contains(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY);
        if (qa.key().equals("RAG-PG-PII-01")) {
            assertThat(response.toString()).doesNotContain("alice@example.com");
            assertThat(embeddings.inputs).noneMatch(value -> value.contains("alice@example.com"));
        }
    }

    @ParameterizedTest(name = "{0}: nearest vector without supported facts stays unanswered")
    @MethodSource("unsupportedCases")
    void retrievedButUnrelatedVectorCannotProduceAnAnswer(QaCase qa) throws Exception {
        User admin = user(UserRole.ADMIN);
        long documentId = createReadyDocument(admin, qa.key(), qa.content());
        approve(admin, documentId);
        processQueuedIndex(documentId);

        // The fake model deliberately gives the one indexed document the same vector as each query.
        // The application passes this candidate to the model, which decides that it does not answer.
        assertRetrievedFromPgVector(qa.question(), qa.content());
        chat.answerOverride.set("INSUFFICIENT_EVIDENCE");
        int previousChatCalls = chat.calls.get();
        JsonNode response = response(query(admin, qa.question()));

        assertThat(response.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.path("answer").isNull()).isTrue();
        assertThat(response.path("sources")).isEmpty();
        assertThat(chat.calls.get()).isEqualTo(previousChatCalls + 1);
        assertThat(chat.promptContents.getLast()).contains(qa.content());
        assertThat(embeddings.tasks).contains(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY);
    }

    @ParameterizedTest(name = "{0}: fixed corpus fixture flows through API, Advisor, and PgVectorStore")
    @MethodSource("fixedFixtureCases")
    void fixedFixtureQuestionUsesLiveRetrievedSourcesAndExpectedVersion(FixtureQa qa) throws Exception {
        User admin = user(UserRole.ADMIN);
        Map<String, Long> idsBySource = new LinkedHashMap<>();
        for (FixtureSource source : qa.sources()) {
            long id = createReadyDocument(admin, source.documentKey(), source.version(), source.content(), source.type());
            idsBySource.put(source.documentKey(), id);
            approve(admin, id);
            processQueuedIndex(id);
            assertRetrievedFromPgVector(qa.question(), source.searchText());
        }

        chat.answerOverride.set(String.join("\n", qa.expectedAnswerPoints()));
        JsonNode response = response(query(admin, qa.question()));

        assertThat(response.path("status").asText()).isEqualTo("ANSWERED");
        assertThat(response.path("answer").asText()).contains(qa.expectedAnswerPoints().getFirst());
        if (qa.fixtureKey().equals("RAG-018")) {
            qa.expectedAnswerPoints().forEach(point -> assertThat(response.path("answer").asText())
                    .as("RAG-018 API answer preserves both role-scoped expiry claims")
                    .contains(point));
        }
        JsonNode sources = response.path("sources");
        List<String> sourceTitles = new java.util.ArrayList<>();
        sources.forEach(source -> sourceTitles.add(source.path("title").asText()));
        assertThat(sourceTitles).containsExactlyInAnyOrderElementsOf(qa.expectedSourceKeys());
        Map<String, String> expectedVersions = qa.sources().stream()
                .filter(source -> qa.expectedSourceKeys().contains(source.documentKey()))
                .collect(java.util.stream.Collectors.toMap(FixtureSource::documentKey, FixtureSource::version));
        sources.forEach(source -> {
            String sourceKey = source.path("title").asText();
            assertThat(source.path("documentId").asLong()).isEqualTo(idsBySource.get(sourceKey));
            assertThat(source.path("version").asText()).isEqualTo(expectedVersions.get(sourceKey));
            assertThat(source.path("excerpt").asText()).isNotBlank();
            assertThat(source.path("relevanceScore").asDouble()).isGreaterThanOrEqualTo(0.45);
        });
    }

    @Test
    void supplierPaymentComparisonWithoutSupportedPaymentFactsStaysInsufficient() throws Exception {
        User admin = user(UserRole.ADMIN);
        String question = "比較 SUP-001 的 MAT-001 與 SUP-002 的 MAT-002 付款條件。";
        Map<String, JsonNode> fixtures = fixtureMap();
        FixtureSource supplierOne = fixtureSource(fixtures.get("RAG-001"), "DOC-SUP-001-CONTRACT", true);
        FixtureSource supplierTwo = fixtureSource(fixtures.get("RAG-004"), "DOC-SUP-002-QUOTATION-SP907", true);
        for (FixtureSource source : List.of(supplierOne, supplierTwo)) {
            long id = createReadyDocument(admin, source.documentKey(), source.version(), source.content(), source.type());
            approve(admin, id);
            processQueuedIndex(id);
        }

        List<Document> retrieved = queryVectorStore.similaritySearch(SearchRequest.builder()
                .query(question).topK(5).similarityThreshold(0.45).build());
        assertThat(retrieved).extracting(Document::getText)
                .anySatisfy(text -> assertThat(text).contains("SUP-001"))
                .anySatisfy(text -> assertThat(text).contains("SUP-002"));
        chat.answerOverride.set("INSUFFICIENT_EVIDENCE");
        int previousChatCalls = chat.calls.get();
        JsonNode response = response(query(admin, question));

        assertThat(response.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.path("answer").isNull()).isTrue();
        assertThat(response.path("sources")).isEmpty();
        assertThat(chat.calls.get()).isEqualTo(previousChatCalls + 1);
        assertThat(chat.promptContents.getLast()).contains("SUP-001", "SUP-002");
    }

    @Test
    void retrievedFactsForAnotherSupplierCannotAnswerRequestedSupplier() throws Exception {
        User admin = user(UserRole.ADMIN);
        Supplier supplierTwo = supplier("SUP-002", "東港貿易社");
        FixtureSource source = fixtureSource(fixtureMap().get("RAG-004"),
                "DOC-SUP-002-QUOTATION-SP907", true);
        long documentId = createReadyDocument(admin, supplierTwo, source.documentKey(), source.version(),
                source.content(), source.type());
        approve(admin, documentId);
        processQueuedIndex(documentId);

        String question = "SUP-001 的 MAT-002 標準交期為幾天？";
        assertRetrievedFromPgVector(question, source.searchText());
        chat.answerOverride.set("INSUFFICIENT_EVIDENCE");
        int previousChatCalls = chat.calls.get();
        JsonNode response = response(query(admin, question));

        assertThat(response.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.path("answer").isNull()).isTrue();
        assertThat(response.path("sources")).isEmpty();
        assertThat(chat.calls.get()).isEqualTo(previousChatCalls + 1);
        assertThat(chat.promptContents.getLast()).contains(source.content());
    }

    @Test
    void retrievedFactsForAnotherMaterialCannotAnswerRequestedMaterial() throws Exception {
        User admin = user(UserRole.ADMIN);
        Supplier supplierOne = supplier("SUP-001", "福爾摩沙原料工坊");
        FixtureSource source = fixtureSource(fixtureMap().get("RAG-001"), "DOC-SUP-001-CONTRACT", true);
        long documentId = createReadyDocument(admin, supplierOne, source.documentKey(), source.version(),
                source.content(), source.type());
        approve(admin, documentId);
        processQueuedIndex(documentId);

        String question = "SUP-001 的 MAT-002 標準交期為幾天？";
        assertRetrievedFromPgVector(question, source.searchText());
        chat.answerOverride.set("INSUFFICIENT_EVIDENCE");
        int previousChatCalls = chat.calls.get();
        JsonNode response = response(query(admin, question));

        assertThat(response.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.path("answer").isNull()).isTrue();
        assertThat(response.path("sources")).isEmpty();
        assertThat(chat.calls.get()).isEqualTo(previousChatCalls + 1);
        assertThat(chat.promptContents.getLast()).contains(source.content());
    }

    @Test
    void conflictingLiveSupplierFactsAreNoLongerFilteredFromModelAnswer() throws Exception {
        User admin = user(UserRole.ADMIN);
        Supplier supplierOne = supplier("SUP-001", "福爾摩沙原料工坊");
        String firstFact = "SUP-001 MAT-001 標準交期為 5 個日曆日。";
        String conflictingFact = "SUP-001 MAT-001 標準交期為 8 個日曆日。";
        long firstId = createReadyDocument(admin, supplierOne, "QA-SUP-001-MAT-001-LEAD-TIME-A", "qa-v1",
                firstFact, DocumentType.QUOTATION);
        long conflictingId = createReadyDocument(admin, supplierOne, "QA-SUP-001-MAT-001-LEAD-TIME-B", "qa-v1",
                conflictingFact, DocumentType.QUOTATION);
        for (long documentId : List.of(firstId, conflictingId)) {
            approve(admin, documentId);
            processQueuedIndex(documentId);
        }

        String question = "SUP-001 的 MAT-001 標準交期為幾天？";
        List<Document> retrieved = queryVectorStore.similaritySearch(SearchRequest.builder()
                .query(question).topK(5).similarityThreshold(0.45).build());
        assertThat(retrieved).extracting(Document::getText)
                .anySatisfy(text -> assertThat(text).contains(firstFact))
                .anySatisfy(text -> assertThat(text).contains(conflictingFact));
        chat.answerOverride.set(firstFact + "；" + conflictingFact);
        JsonNode response = response(query(admin, question));

        assertThat(response.path("status").asText()).isEqualTo("ANSWERED");
        assertThat(response.path("answer").asText()).contains(firstFact, conflictingFact);
        assertThat(response.path("sources")).hasSize(2);
    }

    @Test
    void supportedNarrowDeliveryLeadTimeComparisonUsesSupplierEntitiesAndApprovedSources() throws Exception {
        User admin = user(UserRole.ADMIN);
        Supplier supplierOne = supplier("SUP-001", "福爾摩沙原料工坊");
        Supplier supplierTwo = supplier("SUP-002", "東港貿易社");
        String supplierOneFact = "SUP-001 MAT-001 標準交期為 5 個日曆日。";
        String supplierTwoFact = "SUP-002 MAT-001 標準交期為 8 個日曆日。";
        long supplierOneDocument = createReadyDocument(admin, supplierOne, "QA-SUP-001-MAT-001-QUOTE",
                "qa-current", supplierOneFact, DocumentType.QUOTATION);
        long supplierTwoDocument = createReadyDocument(admin, supplierTwo, "QA-SUP-002-MAT-001-QUOTE",
                "qa-current", supplierTwoFact, DocumentType.QUOTATION);
        for (long documentId : List.of(supplierOneDocument, supplierTwoDocument)) {
            approve(admin, documentId);
            processQueuedIndex(documentId);
        }

        String question = "比較 SUP-001 與 SUP-002 的 MAT-001 標準交期，哪一家較長？";
        List<Document> retrieved = queryVectorStore.similaritySearch(SearchRequest.builder()
                .query(question).topK(5).similarityThreshold(0.45).build());
        assertThat(retrieved).extracting(Document::getText)
                .anySatisfy(text -> assertThat(text).contains(supplierOneFact))
                .anySatisfy(text -> assertThat(text).contains(supplierTwoFact));
        chat.answerOverride.set("SUP-001 的 MAT-001 標準交期為 5 個日曆日；"
                + "SUP-002 的 MAT-001 標準交期為 8 個日曆日；SUP-002 比 SUP-001 長 3 個日曆日。");

        JsonNode response = response(query(admin, question));

        assertThat(response.path("status").asText()).isEqualTo("ANSWERED");
        assertThat(response.path("answer").asText()).contains("SUP-002 比 SUP-001 長 3 個日曆日");
        assertThat(response.path("sources")).hasSize(2);
        assertThat(response.path("sources").findValuesAsText("supplierCode"))
                .containsExactlyInAnyOrder("SUP-001", "SUP-002");
        assertThat(response.path("sources").findValuesAsText("title"))
                .containsExactlyInAnyOrder("QA-SUP-001-MAT-001-QUOTE", "QA-SUP-002-MAT-001-QUOTE");
    }

    @Test
    void staleVectorVersionIsRetrievedButCannotSupplyCurrentSource() throws Exception {
        User admin = user(UserRole.ADMIN);
        String content = "Forklift capacity is 500 kilograms.";
        long documentId = createReadyDocument(admin, "RAG-PG-VERSION", content);
        approve(admin, documentId);
        processQueuedIndex(documentId);
        assertRetrievedFromPgVector("forklift capacity", content);

        DocumentMetadata document = documents.findById(documentId).orElseThrow();
        ReflectionTestUtils.setField(document, "version", "v2");
        documents.saveAndFlush(document);
        chat.answerOverride.set("INSUFFICIENT_EVIDENCE");
        int previousChatCalls = chat.calls.get();
        JsonNode response = response(query(admin, "forklift capacity"));

        assertThat(response.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.path("sources")).isEmpty();
        assertThat(chat.calls.get()).isEqualTo(previousChatCalls + 1);
        assertThat(chat.promptContents.getLast()).contains(content);
    }

    @Test
    void advisorPassesVectorCandidatesDirectlyToModelWithoutPostFiltering() throws Exception {
        User admin = user(UserRole.ADMIN);
        String content = "Forklift capacity is 500 kilograms.";
        long documentId = createReadyDocument(admin, "RAG-PG-UNAPPROVED", content);
        DocumentMetadata document = documents.findById(documentId).orElseThrow();
        List<DocumentChunk> sourceChunks = chunks.findByDocumentIdOrderByChunkIndex(documentId);
        String sourceHash = SemanticBackfillService.sourceHash(sourceChunks);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", documentId);
        metadata.put("documentVersion", document.getVersion());
        metadata.put("generationKey", semanticProperties.generationKey());
        metadata.put("documentGeneration", document.getSemanticIndexGeneration());
        metadata.put("sourceHash", sourceHash);
        metadata.put("chunkIndex", 0);
        metadata.put("startOffset", 0);
        metadata.put("endOffset", content.length());
        metadata.put("documentStatus", "READY");
        metadata.put("outboundApprovalStatus", "APPROVED");
        metadata.put("documentType", DocumentType.PROCUREMENT_SOP.name());
        metadata.put("title", "Forged vector metadata");
        writeVectorStore.add(List.of(Document.builder().id(UUID.randomUUID().toString())
                .text(content).metadata(metadata).build()));
        assertRetrievedFromPgVector("forklift capacity", content);

        chat.answerOverride.set("INSUFFICIENT_EVIDENCE");
        int previousChatCalls = chat.calls.get();
        JsonNode response = response(query(admin, "forklift capacity"));

        assertThat(response.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.path("sources")).isEmpty();
        assertThat(chat.calls.get()).isEqualTo(previousChatCalls + 1);
        assertThat(chat.promptContents.getLast()).contains(content);
    }

    @Test
    void deniedRoleCannotInvokeAdvisorOrEmbeddingProvider() throws Exception {
        User admin = user(UserRole.ADMIN);
        String content = "Forklift capacity is 500 kilograms.";
        long documentId = createReadyDocument(admin, "RAG-PG-AUTH", content);
        approve(admin, documentId);
        processQueuedIndex(documentId);
        embeddings.tasks.clear();
        int previousChatCalls = chat.calls.get();

        User planner = user(UserRole.PRODUCTION_PLANNER);
        assertThat(query(planner, "forklift capacity").getResponse().getStatus()).isEqualTo(403);

        assertThat(embeddings.tasks).doesNotContain(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY);
        assertThat(chat.calls.get()).isEqualTo(previousChatCalls);
    }

    @Test
    void chatProviderFailureAfterVerifiedRetrievalReturns503() throws Exception {
        User admin = user(UserRole.ADMIN);
        String content = "Forklift capacity is 500 kilograms.";
        long documentId = createReadyDocument(admin, "RAG-PG-CHAT-FAILURE", content);
        approve(admin, documentId);
        processQueuedIndex(documentId);
        chat.fail.set(true);
        int previousChatCalls = chat.calls.get();

        JsonNode response = response(query(admin, "forklift capacity"));

        assertThat(response.path("code").asText()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(chat.calls.get()).isEqualTo(previousChatCalls + 1);
        assertThat(chat.promptContents.getLast()).contains(content);
        assertThat(mvc.perform(get("/api/v1/documents").header("Authorization", bearer(admin)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void embeddingProviderFailureDuringAdvisorRetrievalReturns503() throws Exception {
        User admin = user(UserRole.ADMIN);
        String content = "Forklift capacity is 500 kilograms.";
        long documentId = createReadyDocument(admin, "RAG-PG-EMBEDDING-FAILURE", content);
        approve(admin, documentId);
        processQueuedIndex(documentId);
        embeddings.fail.set(true);
        int previousChatCalls = chat.calls.get();

        JsonNode response = response(query(admin, "forklift capacity"));

        assertThat(response.path("code").asText()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(chat.calls.get()).isEqualTo(previousChatCalls);
    }

    private void assertRetrievedFromPgVector(String question, String expectedText) {
        List<Document> retrieved = queryVectorStore.similaritySearch(SearchRequest.builder()
                .query(question).topK(5).similarityThreshold(0.45).build());
        assertThat(retrieved.stream().map(Document::getText).anyMatch(text -> text.contains(expectedText))).isTrue();
    }

    private void processQueuedIndex(long documentId) {
        assertThat(worker.processNext()).isTrue();
        String sourceHash = SemanticBackfillService.sourceHash(
                chunks.findByDocumentIdOrderByChunkIndex(documentId));
        DocumentMetadata document = documents.findById(documentId).orElseThrow();
        long documentGeneration = document.getSemanticIndexGeneration();
        assertThat(jobs.findCurrent(documentId, document.getVersion(), sourceHash, semanticProperties.generationKey(),
                documentGeneration)
                .orElseThrow().status()).isEqualTo(SemanticIndexJobStatus.SUCCEEDED);
        assertThat(semanticStore.state(documentId, document.getVersion()).status()).isEqualTo("READY");
    }

    private long createReadyDocument(User actor, String title, String content) {
        return createReadyDocument(actor, title, "v1", content, DocumentType.PROCUREMENT_SOP);
    }

    private long createReadyDocument(User actor, String title, String version, String content, DocumentType type) {
        return createReadyDocument(actor, null, title, version, content, type);
    }

    private long createReadyDocument(User actor, Supplier supplier, String title, String version, String content,
                                     DocumentType type) {
        DocumentMetadata document = DocumentMetadata.processing(type, supplier,
                title, version, "qa/" + title + ".txt", actor, clock.instant());
        document.markReady(clock.instant());
        document = documents.saveAndFlush(document);
        chunks.saveAndFlush(DocumentChunk.create(document, 0, 0, content.length(), content,
                "[]", clock.instant()));
        return document.getId();
    }

    private Supplier supplier(String code, String name) {
        return suppliers.findByCode(code).orElseGet(() -> suppliers.saveAndFlush(Supplier.create(
                code, name, "Taiwan", BigDecimal.valueOf(80), BigDecimal.valueOf(80), clock.instant())));
    }

    private void approve(User admin, long documentId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/semantic-index/documents/{id}/approve-outbound", documentId)
                        .header("Authorization", bearer(admin)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("status").asText())
                .isEqualTo("APPROVED");
    }

    private MvcResult query(User actor, String question) throws Exception {
        return mvc.perform(post("/api/v1/document-queries")
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("question", question))))
                .andReturn();
    }

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private User user(UserRole role) {
        return users.saveAndFlush(User.create("rag-qa-" + UUID.randomUUID(), "hash", "RAG QA",
                role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(
                new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private static Stream<Arguments> answerableCases() {
        return Stream.of(
                qa("RAG-PG-001", "SUP-001 供應 MAT-001 的標準交期從何時起算、為多久？",
                        "供應商福爾摩沙原料工坊（SUP-001）供應阿薩姆紅茶萃取物（MAT-001），主檔基本單位為 KG。"
                                + "採購單進入正式狀態 ORDERED 後，標準交期為 5 個日曆日。若供應商預見交期可能延後，"
                                + "應在原承諾到貨日之前通知採購窗口並說明新交貨日期；通知不會自行修改採購單上的公司期望到貨日。",
                        "5 個日曆日"),
                qa("RAG-PG-004", "SUP-002 供應 MAT-002 芒果濃縮汁的最低量、包裝增量與交期為何？",
                        "SUP-002 MAT-002 芒果濃縮汁：幣別 TWD，基本計價單位 KG，MOQ 400 KG，包裝倍數 25 KG，"
                                + "標準交期 8 個日曆日，交期由正式 PO 進入 ORDERED 起算。", "MOQ 400 KG"),
                qa("RAG-PG-005", "SUP-007 供應 MAT-010 的最低訂購量、包裝倍數及標準交期為何？",
                        "SUP-007 MAT-010 檸檬汁濃縮液：幣別 TWD，基本計價單位 KG，MOQ 150 KG，"
                                + "包裝倍數 50 KG，標準交期 4 個日曆日，交期由正式 PO 進入 ORDERED 起算。",
                        "MOQ 150 KG"),
                qa("RAG-PG-006", "MAT-001 阿薩姆紅茶萃取物的基本單位與儲存方式為何？",
                        "MAT-001 阿薩姆紅茶萃取物的基本單位為 KG，儲存於陰涼乾燥處、避免陽光直射並保持密封。",
                        "陰涼乾燥處"),
                qa("RAG-PG-007", "MAT-002 的主檔名稱和基本單位是什麼？",
                        "物料代碼 MAT-002，主檔名稱為芒果濃縮汁，基本單位 KG，狀態 ACTIVE。展示物料識別應以 MAT-002 為準，"
                                + "不得與 MAT-007 百香果泥混用。採購與收貨記錄均以 KG 表達數量。", "芒果濃縮汁"),
                qa("RAG-PG-009", "MAT-006 的名稱及計量單位為何？",
                        "物料代碼 MAT-006，主檔名稱為茉莉茶萃取物，基本單位 KG，狀態 ACTIVE。收料及領用記錄均使用 KG，"
                                + "不得因包裝箱數而改變主檔計量單位。", "茉莉茶萃取物"),
                qa("RAG-PG-010", "一般原料到貨發現封口破損或批次標籤缺失時，應怎麼處理？",
                        "如發現物料代碼不符、批次標籤缺失、容器破損、封口異常或可見污染，應將該批隔離並通知採購人員。"
                                + "未完成異常處置前不得將其納入可用庫存。", "隔離並通知採購人員"),
                qa("RAG-PG-011", "MAT-001 的容器標籤至少要顯示什麼？",
                        "MAT-001 容器標籤至少應顯示物料代碼、批次識別及淨重。", "批次識別及淨重"),
                qa("RAG-PG-012", "MAT-010 到貨有滲漏或批次資訊缺漏時，是否可先收一部分合格包裝？",
                        "MAT-010 到貨若有滲漏或批次資訊缺漏，應記錄差異並隔離整筆交付；不支援部分收貨，"
                                + "整筆驗收完成前不得入庫。", "不支援部分收貨"),
                qa("RAG-PG-014", "採購 Kanban 的第一個階段為何？",
                        "採購 Kanban 的第一個階段是 NEW_REQUEST。", "第一個階段是 NEW_REQUEST"),
                qa("RAG-PG-016", "核准後公司的期望日期可以修改嗎？",
                        "文件類型：PROCUREMENT_SOP。採購單的公司期望到貨日與供應商承諾到貨日是兩個用途不同的欄位。"
                                + "採購單核准後，公司期望到貨日不可透過一般草稿編輯方式修改。"
                                + "供應商通知交期延後時，採購人員應記錄通知內容與新承諾日期，並更新供應商承諾到貨日。",
                        "公司期望到貨日不可透過一般草稿編輯方式修改"),
                qa("RAG-PG-029", "SUP-001 的 MAT-001 MOQ 和包裝倍數為何？",
                        "SUP-001/MAT-001 阿薩姆紅茶萃取物：主檔關係 ACTIVE，單位 KG，MOQ 800 KG，"
                                + "包裝倍數 100 KG，標準交期 5 個日曆日，幣別 TWD。", "MOQ 800 KG"),
                qa("RAG-PG-PII-01", "forklift capacity",
                        "Forklift capacity is 500 kilograms. Contact alice@example.com.", "500 kilograms"))
                .map(Arguments::of);
    }

    private static Stream<Arguments> unsupportedCases() {
        String candidate = "The procurement office opens at 09:00.";
        return Stream.of(
                qa("RAG-PG-NO-01", "Where is the supplier's physical address?", candidate, null),
                qa("RAG-PG-NO-02", "What is the product warranty period?", candidate, null),
                qa("RAG-PG-NO-03", "What is the material shelf life?", candidate, null),
                qa("RAG-PG-NO-04", "What information must appear on the container label?", candidate, null),
                qa("RAG-PG-NO-05", "What are the supplier's payment terms?", candidate, null),
                qa("RAG-PG-NO-06", "How much is the freight fee?", candidate, null),
                qa("RAG-PG-NO-07", "What is the standard delivery lead time?", candidate, null),
                qa("RAG-PG-NO-08", "What is the supplier's minimum order quantity?", candidate, null))
                .map(Arguments::of);
    }

    private static QaCase qa(String key, String question, String content, String expectedAnswer) {
        return new QaCase(key, question, content, expectedAnswer);
    }

    private static Stream<Arguments> fixedFixtureCases() {
        Map<String, JsonNode> byKey = fixtureMap();
        List<Arguments> cases = new java.util.ArrayList<>();
        for (String key : FIXTURE_POSITIVE_KEYS) {
            JsonNode fixture = byKey.get(key);
            if (fixture == null || !"ANSWER".equals(fixture.path("expectedStatus").asText())) {
                throw new IllegalStateException("Missing answerable fixture " + key);
            }
            Set<String> expectedSourceKeys = new LinkedHashSet<>();
            fixture.path("expectedSourceKeys").forEach(node -> expectedSourceKeys.add(node.asText()));
            List<FixtureSource> sources = expectedSourceKeys.stream()
                    .map(sourceKey -> fixtureSource(fixture, sourceKey, true))
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            List<String> answerPoints = new java.util.ArrayList<>();
            fixture.path("expectedAnswerPoints").forEach(node -> answerPoints.add(node.asText()));
            cases.add(Arguments.of(new FixtureQa(key, fixture.path("question").asText(), answerPoints,
                    expectedSourceKeys, List.copyOf(sources))));
        }
        return cases.stream();
    }

    private static Map<String, JsonNode> fixtureMap() {
        try {
            Path repositoryRoot = Path.of("..").toAbsolutePath().normalize();
            JsonNode fixtures = new ObjectMapper().readTree(Files.readString(
                    repositoryRoot.resolve("samples/documents/rag-fixtures.json"))).path("fixtures");
            Map<String, JsonNode> byKey = new HashMap<>();
            fixtures.forEach(node -> byKey.put(node.path("fixtureKey").asText(), node));
            return Map.copyOf(byKey);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read fixed RAG QA fixtures", exception);
        }
    }

    private static FixtureSource fixtureSource(JsonNode fixture, String documentKey, boolean preferLongest) {
        List<String> excerpts = new java.util.ArrayList<>();
        fixture.path("answerEvidence").forEach(option -> option.path("sources").forEach(source -> {
            if (documentKey.equals(source.path("documentKey").asText())) {
                String excerpt = source.path("excerpt").asText();
                if (!excerpt.isBlank()) excerpts.add(excerpt);
            }
        }));
        if (excerpts.isEmpty()) {
            throw new IllegalStateException("Fixture " + fixture.path("fixtureKey").asText()
                    + " has no curated evidence for " + documentKey);
        }
        String excerpt = excerpts.stream().max(preferLongest
                ? java.util.Comparator.comparingInt(String::length)
                : java.util.Comparator.comparingInt(String::length).reversed()).orElseThrow();
        String curatedEvidence = excerpt.lines().map(String::strip).filter(line -> !line.isBlank())
                .filter(line -> !line.matches("^(?i:問答|Q\\s*&\\s*A)\\s*[:：].*"))
                .map(line -> line.replaceFirst("^(?i:預期依據)\\s*[:：]\\s*", ""))
                .collect(java.util.stream.Collectors.joining("\n"));
        return catalogSource(documentKey, curatedEvidence);
    }

    private static FixtureSource catalogSource(String documentKey, String content) {
        try {
            Path repositoryRoot = Path.of("..").toAbsolutePath().normalize();
            JsonNode documents = new ObjectMapper().readTree(Files.readString(
                    repositoryRoot.resolve("samples/documents/corpus-catalog.json"))).path("documents");
            for (JsonNode document : documents) {
                if (!documentKey.equals(document.path("documentKey").asText())) continue;
                String searchText = content.lines().filter(line -> line.contains("SUP-") || line.contains("MAT-"))
                        .findFirst().orElseGet(() -> content.lines().filter(line -> !line.isBlank())
                                .findFirst().orElse(content));
                return new FixtureSource(documentKey, document.path("version").asText(),
                        DocumentType.valueOf(document.path("type").asText()), content, searchText);
            }
            throw new IllegalStateException("Missing corpus catalog document " + documentKey);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read corpus document " + documentKey, exception);
        }
    }

    record QaCase(String key, String question, String content, String expectedAnswer) {
        @Override public String toString() { return key; }
    }

    record FixtureQa(String fixtureKey, String question, List<String> expectedAnswerPoints,
                     Set<String> expectedSourceKeys, List<FixtureSource> sources) {
        @Override public String toString() { return fixtureKey; }
    }

    record FixtureSource(String documentKey, String version, DocumentType type, String content, String searchText) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeConfiguration {
        @Bean @Primary FakeEmbeddingModel ragQaFakeEmbeddingModel() { return new FakeEmbeddingModel(); }

        @Bean(name = "ragDocumentEmbeddingModel")
        EmbeddingModel ragDocumentEmbeddingModel(FakeEmbeddingModel model) { return model.documentModel(); }

        @Bean(name = "ragQueryEmbeddingModel")
        EmbeddingModel ragQueryEmbeddingModel(FakeEmbeddingModel model) { return model.queryModel(); }

        @Bean @Primary FakeChatModel ragQaFakeChatModel() {
            return new FakeChatModel();
        }
    }

    static class FakeEmbeddingModel implements EmbeddingModel {
        private final FakeEmbeddingModel shared;
        private final GoogleGenAiTextEmbeddingOptions.TaskType taskType;
        final List<String> inputs = new CopyOnWriteArrayList<>();
        final List<GoogleGenAiTextEmbeddingOptions.TaskType> tasks = new CopyOnWriteArrayList<>();
        final AtomicBoolean fail = new AtomicBoolean();

        FakeEmbeddingModel() {
            shared = this;
            taskType = GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT;
        }

        private FakeEmbeddingModel(FakeEmbeddingModel shared, GoogleGenAiTextEmbeddingOptions.TaskType taskType) {
            this.shared = shared;
            this.taskType = taskType;
        }

        FakeEmbeddingModel documentModel() { return new FakeEmbeddingModel(shared,
                GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT); }

        FakeEmbeddingModel queryModel() { return new FakeEmbeddingModel(shared,
                GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY); }

        void reset() {
            inputs.clear();
            tasks.clear();
            fail.set(false);
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (shared.fail.get()) throw new IllegalStateException("deterministic embedding provider failure");
            shared.inputs.addAll(request.getInstructions());
            shared.tasks.addAll(java.util.Collections.nCopies(request.getInstructions().size(), taskType));
            List<Embedding> values = request.getInstructions().stream()
                    .map(ignored -> {
                        float[] vector = new float[768];
                        vector[0] = 1.0f;
                        return new Embedding(vector, 0);
                    }).toList();
            return new EmbeddingResponse(values);
        }

        @Override
        public float[] embed(Document document) {
            return call(new EmbeddingRequest(List.of(document.getText()), null)).getResult().getOutput();
        }
    }

    static class FakeChatModel implements ChatModel {
        private final ChatOptions options = ChatOptions.builder().build();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean fail = new AtomicBoolean();
        final AtomicReference<String> answerOverride = new AtomicReference<>();
        final List<String> promptContents = new CopyOnWriteArrayList<>();

        void reset() {
            calls.set(0);
            fail.set(false);
            answerOverride.set(null);
            promptContents.clear();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            String contents = prompt.getContents();
            promptContents.add(contents);
            if (fail.get()) throw new IllegalStateException("deterministic chat provider failure");
            String answer = answerOverride.get();
            if (answer == null) answer = "INSUFFICIENT_EVIDENCE";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        @Override public ChatOptions getDefaultOptions() { return options; }
        @Override public ChatOptions getOptions() { return options; }
    }
}

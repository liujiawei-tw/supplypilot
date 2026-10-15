package com.supplypilot.integration;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.SemanticStore;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(SemanticRagIntegrationTests.FakeConfiguration.class)
class SemanticRagIntegrationTests extends AbstractIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void semanticProperties(DynamicPropertyRegistry registry) {
        registry.add("supplypilot.semantic.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("supplypilot.semantic.username", POSTGRES::getUsername);
        registry.add("supplypilot.semantic.password", POSTGRES::getPassword);
        registry.add("supplypilot.semantic.read-enabled", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired DocumentMetadataRepository documents;
    @Autowired DocumentChunkRepository chunks;
    @Autowired JwtTokenService tokens;
    @Autowired Clock clock;
    @Autowired FakeModel model;
    @Autowired SemanticStore store;

    @BeforeEach
    void clean() throws SQLException {
        model.inputs.clear();
        model.tasks.clear();
        model.fail.set(false);
        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
        store.ensureSchema();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM semantic_chunks");
            statement.executeUpdate("DELETE FROM semantic_document_indexes");
            statement.executeUpdate("DELETE FROM semantic_gate_audit");
        }
    }

    @Test
    void backfillRedactsAndGatesCutoverBeforeExactSearch() throws Exception {
        User admin = user(UserRole.ADMIN);
        User manager = user(UserRole.PROCUREMENT_MANAGER);
        User planner = user(UserRole.PRODUCTION_PLANNER);
        long safeId = ready(admin, DocumentType.PROCUREMENT_SOP,
                "Forklift capacity is 500 kilograms. Contact alice@example.com, +886912345678, A123456789.");
        long excludedId = ready(admin, DocumentType.SUPPLIER_CONTRACT,
                "Supplier contract confidential terms.");
        long reviewId = ready(admin, DocumentType.QUALITY_REQUIREMENT,
                "API key: secret-value. Quality checks are daily.");

        mvc.perform(post("/api/v1/semantic-index/documents/{id}/approve-outbound", safeId)
                        .header("Authorization", bearer(planner)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/semantic-index/documents/{id}/exclude-outbound", excludedId)
                        .header("Authorization", bearer(planner)))
                .andExpect(status().isForbidden());
        approve(admin, safeId);
        approve(admin, reviewId);
        exclude(admin, excludedId);

        query(admin, "forklift capacity").andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(planner)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.requiresReview").value(1));
        assertThat(model.inputs).hasSize(1);
        assertThat(model.tasks).containsExactly(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT);
        assertThat(model.inputs.getFirst()).contains("[EMAIL_REDACTED]")
                .contains("[PHONE_REDACTED]", "[ID_REDACTED]")
                .doesNotContain("alice@example.com", "+886912345678", "A123456789",
                        "secret-value", "Supplier contract");
        assertThat(store.state(reviewId, "v1").status()).isEqualTo("REQUIRES_REVIEW");
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        query(admin, "forklift capacity").andExpect(status().isServiceUnavailable());

        DocumentMetadata review = documents.findById(reviewId).orElseThrow();
        review.updateMetadata(null, DocumentStatus.INACTIVE, clock.instant());
        documents.saveAndFlush(review);
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true));
        query(manager, "forklift capacity").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.sources[0].documentId").value(safeId))
                .andExpect(jsonPath("$.sources[0].version").value("v1"))
                .andExpect(jsonPath("$.sources[0].startOffset").value(0))
                .andExpect(jsonPath("$.sources[0].excerpt").value(org.hamcrest.Matchers.containsString(
                        "Forklift capacity is 500 kilograms. Contact [EMAIL_REDACTED], [PHONE_REDACTED], [ID_REDACTED].")))
                .andExpect(jsonPath("$.sources[0].excerpt").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("alice@example.com"))));
        assertThat(model.tasks).contains(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY);
        query(planner, "forklift capacity").andExpect(status().isForbidden());
        query(manager, "unknown topic").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"));

        int calls = model.inputs.size();
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(model.inputs).hasSize(calls);

        DocumentMetadata safe = documents.findById(safeId).orElseThrow();
        safe.updateMetadata(null, DocumentStatus.INACTIVE, clock.instant());
        documents.saveAndFlush(safe);
        query(manager, "forklift capacity").andExpect(status().isServiceUnavailable());
    }

    @Test
    void providerFailureReturns503WhileCoreDocumentsRemainAvailable() throws Exception {
        User admin = user(UserRole.ADMIN);
        long id = ready(admin, DocumentType.PROCUREMENT_SOP, "Forklift capacity is 500 kilograms.");
        approve(admin, id);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true));
        model.fail.set(true);
        query(admin, "forklift capacity").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
        mvc.perform(get("/api/v1/documents").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void failedAndInterruptedBackfillIsRetriedWithoutPartialEvidence() throws Exception {
        User admin = user(UserRole.ADMIN);
        long id = ready(admin, DocumentType.PRODUCT_SPECIFICATION, "Forklift lifting capacity is 500 kilograms.");
        approve(admin, id);
        model.fail.set(true);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.failed").value(1));
        assertThat(store.state(id, "v1").status()).isEqualTo("FAILED");
        assertThat(store.chunkCount(id, "v1")).isZero();
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        model.fail.set(false);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(1));
        assertThat(store.chunkCount(id, "v1")).isEqualTo(1);
        store.mark(id, "v1", "PROCESSING", null, store.state(id, "v1").sourceHash(), 1);
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        query(admin, "forklift lifting").andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(1));
        assertThat(store.chunkCount(id, "v1")).isEqualTo(1);
        assertThat(store.state(id, "v1").attempts()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void exactSimilarityAndGenerationIsolationUseOnlySelectedModel() throws Exception {
        User admin = user(UserRole.ADMIN);
        long forklift = ready(admin, DocumentType.PRODUCT_SPECIFICATION, "Forklift capacity is 500 kilograms.");
        long quality = ready(admin, DocumentType.QUALITY_REQUIREMENT, "Quality inspection happens daily.");
        approve(admin, forklift);
        approve(admin, quality);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(2));
        float[] numbers = new float[768];
        numbers[0] = 1;
        List<Float> query = new java.util.ArrayList<>();
        for (float number : numbers) query.add(number);
        assertThat(store.search(query, 2).getFirst().documentId()).isEqualTo(forklift);
        assertThat(store.search(query, 2).getFirst().score()).isEqualTo(1.0);

        var newGeneration = new SemanticStore(new com.supplypilot.document.semantic.SemanticProperties(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                "another-embedding-model", 3, "another-model-3-v1", false, 5, 0.45,
                java.time.Duration.ofSeconds(5)));
        newGeneration.ensureGeneration();
        assertThat(newGeneration.search(List.of(1f, 0f, 0f), 5)).isEmpty();
        assertThat(store.search(query, 2)).hasSize(2);
    }

    @Test
    void outboundApprovalIsRequiredAndBoundToSourceHash() throws Exception {
        User admin = user(UserRole.ADMIN);
        long id = ready(admin, DocumentType.PROCUREMENT_SOP, "Forklift operating procedure.");
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiresReview").value(1));
        assertThat(store.state(id, "v1").failureReason()).isEqualTo("OUTBOUND_APPROVAL_REQUIRED");
        assertThat(model.inputs).isEmpty();
        approve(admin, id);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(1));
        assertThat(model.inputs).hasSize(1);

        chunks.deleteAllInBatch();
        DocumentMetadata document = documents.findById(id).orElseThrow();
        chunks.saveAndFlush(DocumentChunk.create(document, 0, 0, 30,
                "Forklift procedure has changed.", "[1]", clock.instant()));
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiresReview").value(1));
        assertThat(model.inputs).hasSize(1);
    }

    @Test
    void gateRejectsAlteredChunkEvenWhenCountAndSourceHashMatch() throws Exception {
        User admin = user(UserRole.ADMIN);
        String content = "Forklift operating procedure.";
        long id = ready(admin, DocumentType.PROCUREMENT_SOP, content);
        approve(admin, id);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(1));
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true));

        List<Float> vector = new java.util.ArrayList<>(java.util.Collections.nCopies(768, 0f));
        vector.set(0, 1f);
        store.replace(id, "v1", store.state(id, "v1").sourceHash(),
                List.of(new SemanticStore.SafeChunk(0, 0, content.length(), "altered text", vector)));
        mvc.perform(get("/api/v1/semantic-index/readiness").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        query(admin, "forklift procedure").andExpect(status().isServiceUnavailable());

        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(1));
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true));

        DocumentMetadata changedVersion = documents.findById(id).orElseThrow();
        ReflectionTestUtils.setField(changedVersion, "version", "v2");
        documents.saveAndFlush(changedVersion);
        mvc.perform(get("/api/v1/semantic-index/readiness").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        query(admin, "forklift procedure").andExpect(status().isServiceUnavailable());
    }

    @Test
    void reviewedContractAndQuotationAllowBusinessConceptQueries() throws Exception {
        User admin = user(UserRole.ADMIN);
        String contract = "合約交貨延遲時應依通知流程辦理。";
        String quotation = "供應商報價有效期限應依審核程序確認。";
        ready(admin, DocumentType.SUPPLIER_CONTRACT, contract);
        ready(admin, DocumentType.QUOTATION, quotation);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiresReview").value(2));
        assertThat(model.inputs).isEmpty();

        for (DocumentMetadata document : documents.findAll()) {
            mvc.perform(post("/api/v1/semantic-index/documents/{id}/approve-outbound", document.getId())
                            .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        }
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(2));
        assertThat(model.inputs).containsExactly(contract, quotation);
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true));

        model.inputs.clear();
        List<String> questions = List.of("供應商報價有效期限如何規定？", "合約交貨延遲如何處理？",
                "採購 SOP 的單價審核程序為何？", "供應商折扣政策在哪份文件？");
        for (String question : questions) {
            query(admin, question).andExpect(status().isOk());
        }
        assertThat(model.inputs).containsExactlyElementsOf(questions);

        query(admin, "API key: secret-value").andExpect(status().isBadRequest());
        query(admin, "銀行帳號 1234567890").andExpect(status().isBadRequest());
        query(admin, "單價 NT$1200 是多少？").andExpect(status().isBadRequest());
        assertThat(model.inputs).containsExactlyElementsOf(questions);
        query(admin, "供應商 alice@example.com 的報價期限？").andExpect(status().isOk());
        assertThat(model.inputs.getLast()).contains("[EMAIL_REDACTED]")
                .doesNotContain("alice@example.com");
    }

    @Test
    void sensitiveContractsAndQuotationsStayUnderReviewAcrossBackfillRetries() throws Exception {
        User admin = user(UserRole.ADMIN);
        ready(admin, DocumentType.SUPPLIER_CONTRACT, "匯款銀行帳號 1234567890 應保密。");
        ready(admin, DocumentType.QUOTATION, "報價單價 NT$ 1,200。 ");
        ready(admin, DocumentType.PROCUREMENT_SOP, "API key: secret-value");
        ready(admin, DocumentType.QUALITY_REQUIREMENT, "員工姓名：王小明");
        for (DocumentMetadata document : documents.findAll()) {
            mvc.perform(post("/api/v1/semantic-index/documents/{id}/approve-outbound", document.getId())
                            .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUIRES_REVIEW"));
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.requiresReview").value(4));
        }
        assertThat(model.inputs).isEmpty();
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.requiresReview").value(4));
    }

    @Test
    void emptyOrAllReviewedExclusionsCannotPassGate() throws Exception {
        User admin = user(UserRole.ADMIN);
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.ready").value(0));
        query(admin, "forklift procedure").andExpect(status().isServiceUnavailable());

        long id = ready(admin, DocumentType.QUOTATION, "供應商報價程序。");
        exclude(admin, id);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.skipped").value(1));
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.ready").value(0))
                .andExpect(jsonPath("$.skipped").value(1));
        assertThat(model.inputs).isEmpty();
        query(admin, "報價程序").andExpect(status().isServiceUnavailable());
    }

    @Test
    void latestFailedAuditRevokesEarlierPass() throws Exception {
        User admin = user(UserRole.ADMIN);
        long id = ready(admin, DocumentType.QUOTATION, "供應商報價程序。");
        approve(admin, id);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(1));
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true));
        assertThat(store.hasPassingAudit()).isTrue();

        exclude(admin, id);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.skipped").value(1));
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        assertThat(store.hasPassingAudit()).isFalse();
        query(admin, "報價程序").andExpect(status().isServiceUnavailable());
    }

    @Test
    void stalePostgresCandidatesDoNotHideValidMySqlEvidence() throws Exception {
        User admin = user(UserRole.ADMIN);
        long validId = ready(admin, DocumentType.PROCUREMENT_SOP,
                "Forklift operating procedure.");
        approve(admin, validId);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true));

        List<Float> vector = new java.util.ArrayList<>(java.util.Collections.nCopies(768, 0f));
        vector.set(0, 1f);
        for (long staleId = -30; staleId < 0; staleId++) {
            store.mark(staleId, "v1", "PROCESSING", null, "stale", 1);
            store.replace(staleId, "v1", "stale",
                    List.of(new SemanticStore.SafeChunk(0, 0, 5, "stale", vector)));
        }
        query(admin, "forklift procedure").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.sources[0].documentId").value(validId));
    }

    private org.springframework.test.web.servlet.ResultActions query(User user, String question) throws Exception {
        return mvc.perform(post("/api/v1/document-queries").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"" + question + "\"}"));
    }

    private void approve(User admin, long documentId) throws Exception {
        mvc.perform(post("/api/v1/semantic-index/documents/{id}/approve-outbound", documentId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceHash").isString())
                .andExpect(jsonPath("$.approvedBy").value(admin.getId()));
    }

    private void exclude(User admin, long documentId) throws Exception {
        mvc.perform(post("/api/v1/semantic-index/documents/{id}/exclude-outbound", documentId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SKIPPED"));
    }

    private long ready(User actor, DocumentType type, String content) {
        DocumentMetadata document = DocumentMetadata.processing(type, null, "Test document", "v1",
                "unused.txt", actor, clock.instant());
        document.markReady(clock.instant());
        document = documents.saveAndFlush(document);
        chunks.saveAndFlush(DocumentChunk.create(document, 0, 0, content.length(), content,
                "[1]", clock.instant()));
        return document.getId();
    }

    private User user(UserRole role) {
        return users.saveAndFlush(User.create("semantic-" + UUID.randomUUID(), "hash", "Semantic User",
                role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(
                new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeConfiguration {
        @Bean @Primary FakeModel fakeEmbeddingModel() { return new FakeModel(); }
    }

    static class FakeModel implements EmbeddingModel {
        final List<String> inputs = new CopyOnWriteArrayList<>();
        final List<GoogleGenAiTextEmbeddingOptions.TaskType> tasks = new CopyOnWriteArrayList<>();
        final AtomicBoolean fail = new AtomicBoolean();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (fail.get()) throw new IllegalStateException("fake provider unavailable");
            String content = request.getInstructions().getFirst();
            inputs.add(content);
            GoogleGenAiTextEmbeddingOptions options =
                    (GoogleGenAiTextEmbeddingOptions) request.getOptions();
            assertThat(options.getDimensions()).isEqualTo(768);
            tasks.add(options.getTaskType());
            float[] vector = new float[768];
            vector[content.toLowerCase().contains("forklift") ? 0 :
                    content.toLowerCase().contains("quality") ? 1 : 2] = 1.0f;
            return new EmbeddingResponse(List.of(new Embedding(vector, 0)));
        }

        @Override
        public float[] embed(Document document) {
            return call(new EmbeddingRequest(List.of(document.getText()), null)).getResult().getOutput();
        }
    }
}

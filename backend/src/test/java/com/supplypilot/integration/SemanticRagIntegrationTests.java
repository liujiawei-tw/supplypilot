package com.supplypilot.integration;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.rag.LocalLexicalEmbeddingProvider;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.SemanticStore;
import com.supplypilot.document.semantic.SemanticIndexJob;
import com.supplypilot.document.semantic.SemanticIndexJobRepository;
import com.supplypilot.document.semantic.SemanticIndexJobStatus;
import com.supplypilot.document.semantic.SemanticIndexWorker;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "supplypilot.semantic.indexing.max-retry-delay=PT60S")
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
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenService tokens;
    @Autowired Clock clock;
    @Autowired FakeModel model;
    @Autowired SemanticStore store;
    @Autowired SemanticIndexWorker worker;
    @Autowired SemanticIndexJobRepository indexJobs;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() throws SQLException {
        model.inputs.clear();
        model.tasks.clear();
        model.transactionStates.clear();
        model.fail.set(false);
        model.failure.set(null);
        model.blockNext.set(false);
        model.providerStarted = new CountDownLatch(0);
        model.releaseProvider = new CountDownLatch(0);
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
    void outboundApprovalAndPerDocumentPublicationAllowQueriesWithoutGlobalGate() throws Exception {
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

        query(admin, "forklift capacity").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"));
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(planner)))
                .andExpect(status().isForbidden());
        model.inputs.clear();
        model.tasks.clear();
        model.transactionStates.clear();
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.requiresReview").value(1));
        assertThat(model.inputs).hasSize(1);
        assertThat(model.tasks).containsExactly(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT);
        assertThat(model.inputs.getFirst()).contains("[EMAIL]")
                .contains("[PHONE]", "[ID]")
                .doesNotContain("alice@example.com", "+886912345678", "A123456789",
                        "secret-value", "Supplier contract");
        assertThat(store.state(reviewId, "v1").status()).isEqualTo("REQUIRES_REVIEW");
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        query(admin, "forklift capacity").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"));

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
                        "Forklift capacity is 500 kilograms. Contact [EMAIL]")))
                .andExpect(jsonPath("$.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("[PHONE]")))
                .andExpect(jsonPath("$.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("[ID]")))
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
        query(manager, "forklift capacity").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"));
    }


    @Test
    void approvalQueuesDurableWorkAndWorkerPublishesOutsideMysqlTransaction() throws Exception {
        User admin = user(UserRole.ADMIN);
        long id = ready(admin, DocumentType.PROCUREMENT_SOP,
                "Forklift capacity is 500 kilograms. Contact alice@example.com.");
        approve(admin, id);

        assertThat(model.inputs).isEmpty();
        mvc.perform(get("/api/v1/semantic-index/jobs").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].documentId").value(id))
                .andExpect(jsonPath("$[0].status").value("QUEUED"));
        assertThat(worker.processNext()).isTrue();
        assertThat(model.inputs).hasSize(1);
        assertThat(model.inputs.getFirst()).contains("[EMAIL]").doesNotContain("alice@example.com");
        assertThat(model.transactionStates).containsExactly(false);
        assertThat(store.state(id, "v1").status()).isEqualTo("READY");
        assertThat(store.state(id, "v1").publicationGeneration())
                .isEqualTo(documents.findById(id).orElseThrow().getSemanticIndexGeneration());
        assertThat(indexJobs.latest(10)).filteredOn(job -> job.documentId() == id)
                .singleElement().satisfies(job -> assertThat(job.status().name()).isEqualTo("SUCCEEDED"));
    }

    @Test
    void duplicateApprovalEventsCreateOnlyOneDurableJob() throws Exception {
        User admin = user(UserRole.ADMIN);
        long id = ready(admin, DocumentType.PROCUREMENT_SOP, "Forklift operating procedure.");

        approve(admin, id);
        long generation = documents.findById(id).orElseThrow().getSemanticIndexGeneration();
        approve(admin, id);

        assertThat(documents.findById(id).orElseThrow().getSemanticIndexGeneration()).isEqualTo(generation);
        assertThat(indexJobs.latest(10)).filteredOn(job -> job.documentId() == id)
                .singleElement().satisfies(job -> {
                    assertThat(job.status().name()).isEqualTo("QUEUED");
                    assertThat(job.documentGeneration()).isEqualTo(generation);
                });
        assertThat(model.inputs).isEmpty();
    }

    @Test
    void concurrentWorkersClaimQueuedIndexJobExactlyOnce() throws Exception {
        User admin = user(UserRole.ADMIN);
        long documentId = ready(admin, DocumentType.PROCUREMENT_SOP, "Forklift operating procedure.");
        approve(admin, documentId);

        Instant now = clock.instant();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyToClaim = new CountDownLatch(2);
        CountDownLatch startTogether = new CountDownLatch(1);
        try {
            Future<Optional<SemanticIndexJob>> first = executor.submit(() -> {
                readyToClaim.countDown();
                if (!startTogether.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start concurrent index claim");
                }
                return indexJobs.claimNext(now, now.plusSeconds(30), 4);
            });
            Future<Optional<SemanticIndexJob>> second = executor.submit(() -> {
                readyToClaim.countDown();
                if (!startTogether.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start concurrent index claim");
                }
                return indexJobs.claimNext(now, now.plusSeconds(30), 4);
            });

            assertThat(readyToClaim.await(10, TimeUnit.SECONDS)).isTrue();
            startTogether.countDown();
            Optional<SemanticIndexJob> firstClaim = first.get(15, TimeUnit.SECONDS);
            Optional<SemanticIndexJob> secondClaim = second.get(15, TimeUnit.SECONDS);

            assertThat(firstClaim.isPresent()).isNotEqualTo(secondClaim.isPresent());
            SemanticIndexJob claimed = firstClaim.orElseGet(secondClaim::orElseThrow);
            assertThat(claimed.documentId()).isEqualTo(documentId);
            assertThat(claimed.attempts()).isEqualTo(1);
            assertThat(claimed.status()).isEqualTo(SemanticIndexJobStatus.PROCESSING);
        } finally {
            startTogether.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void expiredProcessingLeaseIsRecoveredByAnotherWorkerAttempt() throws Exception {
        User admin = user(UserRole.ADMIN);
        long id = ready(admin, DocumentType.PROCUREMENT_SOP, "Forklift operating procedure.");
        approve(admin, id);

        var interrupted = indexJobs.claimNext(clock.instant(), clock.instant().plusSeconds(30), 4)
                .orElseThrow();
        assertThat(interrupted.status().name()).isEqualTo("PROCESSING");
        assertThat(interrupted.attempts()).isEqualTo(1);
        jdbc.update("UPDATE semantic_index_jobs SET lease_until = ? WHERE id = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), interrupted.id());

        assertThat(worker.processNext()).isTrue();
        assertThat(indexJobs.findById(interrupted.id()).orElseThrow()).satisfies(job -> {
            assertThat(job.status().name()).isEqualTo("SUCCEEDED");
            assertThat(job.attempts()).isEqualTo(2);
        });
        assertThat(model.inputs).containsExactly("Forklift operating procedure.");
    }

    @Test
    void failedDocumentRetriesAreBoundedAndDoNotCloseOtherPublishedDocuments() throws Exception {
        User admin = user(UserRole.ADMIN);
        long publishedId = ready(admin, DocumentType.PROCUREMENT_SOP,
                "Forklift capacity is 500 kilograms.");
        approve(admin, publishedId);
        assertThat(worker.processNext()).isTrue();

        long failingId = ready(admin, DocumentType.QUALITY_REQUIREMENT,
                "Quality checks are performed daily.");
        approve(admin, failingId);
        HttpHeaders retryHeaders = new HttpHeaders();
        retryHeaders.set("Retry-After", "120");
        model.failure.set(new RestClientResponseException("provider rate limit",
                HttpStatusCode.valueOf(429), "Too Many Requests", retryHeaders,
                "private provider body".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(worker.processNext()).isTrue();
            SemanticIndexJob retryJob = indexJobs.latest(10).stream()
                    .filter(job -> job.documentId() == failingId).findFirst().orElseThrow();
            if (attempt == 0) {
                assertThat(retryJob.nextAttemptAt())
                        .isAfterOrEqualTo(clock.instant().plusSeconds(120));
            }
            if (attempt < 3) {
                assertThat(retryJob.status()).isEqualTo(SemanticIndexJobStatus.RETRY_WAIT);
                jdbc.update("UPDATE semantic_index_jobs SET next_attempt_at = ? WHERE id = ?",
                        Timestamp.from(clock.instant()), retryJob.id());
            }
        }
        var failedJob = indexJobs.latest(10).stream().filter(job -> job.documentId() == failingId)
                .findFirst().orElseThrow();
        assertThat(failedJob.status().name()).isEqualTo("FAILED");
        assertThat(failedJob.attempts()).isEqualTo(4);
        assertThat(failedJob.failureCode()).isEqualTo("PROVIDER_RATE_LIMITED");

        User manager = user(UserRole.PROCUREMENT_MANAGER);
        mvc.perform(get("/api/v1/semantic-index/jobs").header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/semantic-index/jobs/{jobId}/retry", failedJob.id())
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());

        model.fail.set(false);
        model.failure.set(null);
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        query(admin, "forklift capacity").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.sources[0].documentId").value(publishedId));
        assertThat(store.state(publishedId, "v1").status()).isEqualTo("READY");

        mvc.perform(post("/api/v1/semantic-index/jobs/{jobId}/retry", failedJob.id())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.retryCount").value(1));
        assertThat(worker.processNext()).isTrue();
        assertThat(indexJobs.latest(10)).filteredOn(job -> job.documentId() == failingId)
                .singleElement().satisfies(job -> {
                    assertThat(job.status().name()).isEqualTo("SUCCEEDED");
                    assertThat(job.retryCount()).isEqualTo(1);
                    assertThat(job.attempts()).isEqualTo(1);
                });
    }

    @Test
    void oldGenerationMarksCannotOverwriteNewerReadyPublication() {
        long id = 841L;
        List<Float> vector = new java.util.ArrayList<>(java.util.Collections.nCopies(768, 0f));
        vector.set(0, 1f);
        List<SemanticStore.SourceChunk> currentSource =
                List.of(new SemanticStore.SourceChunk(0, 0, 6, "newer"));
        assertThat(store.publish(id, "v1", "newer-hash", 2,
                List.of(new SemanticStore.SafeChunk(0, 0, 6, "newer", vector)))).isTrue();

        store.mark(id, "v1", "PROCESSING", null, "old-processing-hash", 1, 1);
        store.mark(id, "v1", "FAILED", "PROVIDER_UNAVAILABLE", "old-failed-hash", 1, 1);

        assertThat(store.state(id, "v1").status()).isEqualTo("READY");
        assertThat(store.state(id, "v1").publicationGeneration()).isEqualTo(2);
        assertThat(store.state(id, "v1").sourceHash()).isEqualTo("newer-hash");
        assertThat(store.matchesSource(id, "v1", currentSource)).isTrue();
    }

    @Test
    void sameGenerationProcessingMarkCannotReleaseReadyHashFence() {
        long id = 842L;
        List<Float> vector = new java.util.ArrayList<>(java.util.Collections.nCopies(768, 0f));
        vector.set(0, 1f);
        List<SemanticStore.SourceChunk> currentSource =
                List.of(new SemanticStore.SourceChunk(0, 0, 6, "newer"));
        assertThat(store.publish(id, "v1", "newer-hash", 2,
                List.of(new SemanticStore.SafeChunk(0, 0, 6, "newer", vector)))).isTrue();

        store.mark(id, "v1", "PROCESSING", null, "newer-hash", 1, 2);
        assertThat(store.state(id, "v1").status()).isEqualTo("READY");
        assertThat(store.publish(id, "v1", "newer-hash", 2,
                List.of(new SemanticStore.SafeChunk(0, 0, 6, "newer", vector)))).isTrue();

        store.mark(id, "v1", "PROCESSING", null, "stale-same-generation-hash", 1, 2);
        assertThat(store.publish(id, "v1", "stale-same-generation-hash", 2,
                List.of(new SemanticStore.SafeChunk(0, 0, 5, "stale", vector)))).isFalse();

        assertThat(store.state(id, "v1").status()).isEqualTo("READY");
        assertThat(store.state(id, "v1").publicationGeneration()).isEqualTo(2);
        assertThat(store.state(id, "v1").sourceHash()).isEqualTo("newer-hash");
        assertThat(store.matchesSource(id, "v1", currentSource)).isTrue();
    }

    @Test
    void staleBackfillCannotReplaceNewerAsyncWorkerPublication() throws Exception {
        User admin = user(UserRole.ADMIN);
        String olderContent = "Forklift capacity is 500 kilograms.";
        long documentId = ready(admin, DocumentType.PROCUREMENT_SOP, olderContent);
        approve(admin, documentId);
        long oldGeneration = documents.findById(documentId).orElseThrow().getSemanticIndexGeneration();

        model.providerStarted = new CountDownLatch(1);
        model.releaseProvider = new CountDownLatch(1);
        model.blockNext.set(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> oldBackfill = executor.submit(() -> mvc.perform(
                            post("/api/v1/semantic-index/backfill")
                                    .param("afterId", "0")
                                    .param("limit", "100")
                                    .header("Authorization", bearer(admin)))
                    .andReturn().getResponse().getStatus());

            assertThat(model.providerStarted.await(10, TimeUnit.SECONDS)).isTrue();

            String newerContent = "Quality checks are performed daily.";
            DocumentMetadata document = documents.findById(documentId).orElseThrow();
            jdbc.update("DELETE FROM document_chunks WHERE document_id = ?", documentId);
            String lexicalVector = new LocalLexicalEmbeddingProvider().embed(newerContent).stream()
                    .map(String::valueOf).collect(Collectors.joining(","));
            chunks.saveAndFlush(DocumentChunk.create(document, 0, 0, newerContent.length(), newerContent,
                    lexicalVector, clock.instant()));
            approve(admin, documentId);
            long newGeneration = documents.findById(documentId).orElseThrow().getSemanticIndexGeneration();
            assertThat(newGeneration).isGreaterThan(oldGeneration);

            SemanticIndexJob oldClaim = indexJobs.claimNext(clock.instant(),
                    clock.instant().plusSeconds(30), 4).orElseThrow();
            assertThat(oldClaim.documentId()).isEqualTo(documentId);
            assertThat(oldClaim.documentGeneration()).isEqualTo(oldGeneration);
            assertThat(indexJobs.supersede(oldClaim.id(), oldClaim.attempts(),
                    "SOURCE_GENERATION_ADVANCED", clock.instant())).isTrue();

            assertThat(worker.processNext()).isTrue();
            assertThat(store.state(documentId, "v1").status()).isEqualTo("READY");
            assertThat(store.state(documentId, "v1").publicationGeneration()).isEqualTo(newGeneration);
            assertThat(store.matchesSource(documentId, "v1", List.of(new SemanticStore.SourceChunk(
                    0, 0, newerContent.length(), newerContent)))).isTrue();

            model.releaseProvider.countDown();
            assertThat(oldBackfill.get(15, TimeUnit.SECONDS)).isEqualTo(200);

            assertThat(store.state(documentId, "v1").status()).isEqualTo("READY");
            assertThat(store.state(documentId, "v1").publicationGeneration()).isEqualTo(newGeneration);
            assertThat(store.state(documentId, "v1").sourceHash())
                    .isEqualTo(indexJobs.latest(20).stream()
                            .filter(job -> job.documentId() == documentId)
                            .filter(job -> job.documentGeneration() == newGeneration)
                            .findFirst().orElseThrow().sourceHash());
            assertThat(store.matchesSource(documentId, "v1", List.of(new SemanticStore.SourceChunk(
                    0, 0, newerContent.length(), newerContent)))).isTrue();
        } finally {
            model.releaseProvider.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void olderPublicationGenerationCannotReplaceNewerPgvectorRows() {
        long id = 840L;
        List<Float> vector = new java.util.ArrayList<>(java.util.Collections.nCopies(768, 0f));
        vector.set(0, 1f);
        assertThat(store.publish(id, "v1", "newer-hash", 2,
                List.of(new SemanticStore.SafeChunk(0, 0, 6, "newer", vector)))).isTrue();
        assertThat(store.publish(id, "v1", "older-hash", 1,
                List.of(new SemanticStore.SafeChunk(0, 0, 5, "older", vector)))).isFalse();

        assertThat(store.state(id, "v1").publicationGeneration()).isEqualTo(2);
        assertThat(store.state(id, "v1").sourceHash()).isEqualTo("newer-hash");
        assertThat(store.matchesSource(id, "v1",
                List.of(new SemanticStore.SourceChunk(0, 0, 6, "newer")))).isTrue();
        assertThat(store.publish(id, "v1", "different-hash", 2,
                List.of(new SemanticStore.SafeChunk(0, 0, 5, "stale", vector)))).isFalse();
        assertThatThrownBy(() -> store.publish(id, "v1", "failed-generation", 3,
                List.of(new SemanticStore.SafeChunk(0, 0, 5, "partial", List.of(1.0f)))))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.state(id, "v1").status()).isEqualTo("READY");
        assertThat(store.state(id, "v1").publicationGeneration()).isEqualTo(2);
        assertThat(store.state(id, "v1").sourceHash()).isEqualTo("newer-hash");
        assertThat(store.matchesSource(id, "v1",
                List.of(new SemanticStore.SourceChunk(0, 0, 6, "newer")))).isTrue();
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
        assertThat(store.state(id, "v1").status()).isEqualTo("READY");
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true));
        query(admin, "forklift lifting").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"));
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
    void documentQueryFusesMysqlAndPgvectorEvidenceOnceInReciprocalRankOrder() throws Exception {
        User admin = user(UserRole.ADMIN);
        String firstContent = "Forklift capacity is 500 kilograms.";
        String secondContent = "Forklift capacity is 600 kilograms. Operators should inspect the load before every shift.";
        long firstId = ready(admin, DocumentType.PRODUCT_SPECIFICATION, firstContent);
        long secondId = ready(admin, DocumentType.PRODUCT_SPECIFICATION, secondContent);
        approve(admin, firstId);
        approve(admin, secondId);

        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(2));
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true));

        String response = query(admin, "forklift capacity")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode sources = objectMapper.readTree(response).path("sources");

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).path("documentId").asLong()).isEqualTo(firstId);
        assertThat(sources.get(0).path("excerpt").asText()).isEqualTo(firstContent);
        assertThat(sources.get(0).path("relevanceScore").asDouble()).isEqualTo(1.0);
        assertThat(sources.get(1).path("documentId").asLong()).isEqualTo(secondId);
        assertThat(sources.get(1).path("excerpt").asText()).isEqualTo(secondContent);
        assertThat(sources.get(1).path("relevanceScore").asDouble())
                .isCloseTo(61.0 / 62.0, org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(model.tasks).contains(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY);
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
        store.publish(id, "v1", store.state(id, "v1").sourceHash(), 1,
                List.of(new SemanticStore.SafeChunk(0, 0, content.length(), "altered text", vector)));
        mvc.perform(get("/api/v1/semantic-index/readiness").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        query(admin, "forklift procedure").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"));

        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(1));
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true));

        DocumentMetadata changedVersion = documents.findById(id).orElseThrow();
        ReflectionTestUtils.setField(changedVersion, "version", "v2");
        documents.saveAndFlush(changedVersion);
        mvc.perform(get("/api/v1/semantic-index/readiness").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        query(admin, "forklift procedure").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"));
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
        assertThat(model.inputs.getLast()).contains("[EMAIL]")
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
        query(admin, "forklift procedure").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"));
        model.inputs.clear();
        model.tasks.clear();
        model.transactionStates.clear();

        long id = ready(admin, DocumentType.QUOTATION, "供應商報價程序。");
        exclude(admin, id);
        mvc.perform(post("/api/v1/semantic-index/backfill").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.skipped").value(1));
        mvc.perform(post("/api/v1/semantic-index/gate-audit").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.ready").value(0))
                .andExpect(jsonPath("$.skipped").value(1));
        assertThat(model.inputs).isEmpty();
        query(admin, "報價程序").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"));
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
        query(admin, "報價程序").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"));
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
            store.publish(staleId, "v1", "stale", 0,
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
        String embedding = new LocalLexicalEmbeddingProvider().embed(content).stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        chunks.saveAndFlush(DocumentChunk.create(document, 0, 0, content.length(), content,
                embedding, clock.instant()));
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
        final AtomicBoolean blockNext = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<RuntimeException> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        final List<Boolean> transactionStates = new CopyOnWriteArrayList<>();
        volatile CountDownLatch providerStarted = new CountDownLatch(0);
        volatile CountDownLatch releaseProvider = new CountDownLatch(0);

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (blockNext.compareAndSet(true, false)) {
                providerStarted.countDown();
                try {
                    if (!releaseProvider.await(12, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fake provider latch timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fake provider interrupted", exception);
                }
            }
            RuntimeException configuredFailure = failure.get();
            if (configuredFailure != null) throw configuredFailure;
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

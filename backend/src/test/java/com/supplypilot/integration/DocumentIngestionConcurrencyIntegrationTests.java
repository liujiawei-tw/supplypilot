package com.supplypilot.integration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.rag.SpringAiDocumentProcessingService;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(DocumentIngestionConcurrencyIntegrationTests.ConcurrencyConfiguration.class)
class DocumentIngestionConcurrencyIntegrationTests extends AbstractIntegrationTest {

    private static final long AWAIT_SECONDS = 15;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private DocumentMetadataRepository documents;
    @Autowired private DocumentChunkRepository chunks;
    @Autowired private JwtTokenService tokens;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SpringAiDocumentProcessingService documentProcessing;
    @Autowired private ConcurrencyProbe probe;

    @BeforeEach
    void cleanDocuments() {
        probe.clear();
        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
    }

    @AfterEach
    void clearProbe() {
        probe.clear();
    }

    @Test
    void concurrentIngestionsSerializeAndLeaveOneCompleteChunkSet() throws Exception {
        User admin = user();
        String text = multiChunkText();
        long documentId = uploadTxt(admin, text);
        ProbeSession session = probe.begin(Mode.CONCURRENT_SUCCESS);

        Pair results = runCompetingIngestions(documentId, admin, session);

        assertThat(results.first().getResponse().getStatus()).isEqualTo(200);
        assertThat(results.second().getResponse().getStatus()).isEqualTo(200);
        assertCompleteIndex(documentId, text);
        assertThat(documents.findById(documentId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(session.transactionObservedDuringProcessing.get()).isFalse();
        assertDistinctTransactions(session);
    }

    @Test
    void failedSecondReplacementRollsBackWithoutDamagingCommittedIndex() throws Exception {
        User admin = user();
        String text = multiChunkText();
        long documentId = uploadTxt(admin, text);
        ProbeSession session = probe.begin(Mode.FAIL_SECOND_REPLACEMENT);

        Pair results = runCompetingIngestions(documentId, admin, session);

        assertThat(results.first().getResponse().getStatus()).isEqualTo(200);
        assertThat(results.second().getResponse().getStatus()).isEqualTo(500);
        assertCompleteIndex(documentId, text);
        assertThat(documents.findById(documentId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(session.transactionObservedDuringProcessing.get()).isFalse();
        assertDistinctTransactions(session);
    }

    @Test
    void inactiveUpdateCommittedDuringParsingCannotBeOverwrittenByIngestion() throws Exception {
        User admin = user();
        long documentId = uploadTxt(admin, "Supplier payment terms are net 30 days.");
        ProbeSession session = probe.begin(Mode.PAUSE_DURING_PARSING);
        ExecutorService ingestionExecutor = executor("ingest-first");
        try {
            Future<MvcResult> ingestion = ingestionExecutor.submit(() -> ingest(documentId, admin));
            session.awaitProcessing();

            mockMvc.perform(patch("/api/v1/documents/{id}", documentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"INACTIVE\"}")
                            .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("INACTIVE"));

            session.releaseProcessing.countDown();
            MvcResult result = ingestion.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asText())
                    .isEqualTo("VALIDATION_FAILED");
        } finally {
            session.releaseProcessing.countDown();
            ingestionExecutor.shutdownNow();
        }

        assertThat(documents.findById(documentId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.INACTIVE);
        assertThat(chunks.countByDocumentId(documentId)).isZero();
        assertThat(session.transactionObservedDuringProcessing.get()).isFalse();
    }

    private Pair runCompetingIngestions(long documentId, User actor, ProbeSession session) throws Exception {
        ExecutorService firstExecutor = executor("ingest-first");
        ExecutorService secondExecutor = executor("ingest-second");
        try {
            Future<MvcResult> first = firstExecutor.submit(() -> ingest(documentId, actor));
            session.awaitFirstSave();
            Future<MvcResult> second = secondExecutor.submit(() -> ingest(documentId, actor));
            session.awaitSecondLockAttempt();
            session.releaseFirstSave.countDown();
            return new Pair(first.get(AWAIT_SECONDS, TimeUnit.SECONDS), second.get(AWAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            session.releaseFirstSave.countDown();
            firstExecutor.shutdownNow();
            secondExecutor.shutdownNow();
        }
    }

    private MvcResult ingest(long documentId, User actor) throws Exception {
        return mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                        .header("Authorization", bearer(actor)))
                .andReturn();
    }

    private void assertCompleteIndex(long documentId, String text) {
        List<SpringAiDocumentProcessingService.SourceChunk> expected = documentProcessing.split(text);
        List<DocumentChunk> actual = chunks.findByDocumentIdOrderByChunkIndex(documentId);
        assertThat(actual).hasSize(expected.size());
        assertThat(actual).extracting(DocumentChunk::getChunkIndex).doesNotHaveDuplicates();
        assertThat(actual).extracting(DocumentChunk::getEmbedding).containsOnly("[]");
        for (int index = 0; index < expected.size(); index++) {
            SpringAiDocumentProcessingService.SourceChunk expectedChunk = expected.get(index);
            DocumentChunk actualChunk = actual.get(index);
            assertThat(actualChunk.getChunkIndex()).isEqualTo(expectedChunk.index());
            assertThat(actualChunk.getStartOffset()).isEqualTo(expectedChunk.startOffset());
            assertThat(actualChunk.getEndOffset()).isEqualTo(expectedChunk.endOffset());
            assertThat(actualChunk.getContent()).isEqualTo(expectedChunk.content());
        }
    }

    private void assertDistinctTransactions(ProbeSession session) {
        assertThat(session.firstPersistenceContext.get()).isNotNull();
        assertThat(session.secondPersistenceContext.get()).isNotNull()
                .isNotSameAs(session.firstPersistenceContext.get());
    }

    private long uploadTxt(User actor, String text) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "contract.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
        String response = mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .param("documentType", "SUPPLIER_CONTRACT")
                        .param("title", "Supplier Contract").param("version", "v7")
                        .header("Authorization", bearer(actor)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private User user() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return users.saveAndFlush(User.create("concurrency-" + suffix,
                "hash", "Concurrency User", UserRole.ADMIN, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private String multiChunkText() {
        return ("Supplier payment terms are net 30 days. Contract renewal is annual. "
                + "Delivery acceptance requires an inspection report. ").repeat(30);
    }

    private ExecutorService executor(String threadName) {
        return Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, threadName));
    }

    private record Pair(MvcResult first, MvcResult second) {
    }

    enum Mode {
        CONCURRENT_SUCCESS,
        FAIL_SECOND_REPLACEMENT,
        PAUSE_DURING_PARSING
    }

    static final class ProbeSession {
        private final Mode mode;
        private final CountDownLatch firstSave = new CountDownLatch(1);
        private final CountDownLatch releaseFirstSave = new CountDownLatch(1);
        private final CountDownLatch secondLockAttempt = new CountDownLatch(1);
        private final CountDownLatch processing = new CountDownLatch(1);
        private final CountDownLatch releaseProcessing = new CountDownLatch(1);
        private final AtomicBoolean transactionObservedDuringProcessing = new AtomicBoolean();
        private final AtomicReference<EntityManager> firstPersistenceContext = new AtomicReference<>();
        private final AtomicReference<EntityManager> secondPersistenceContext = new AtomicReference<>();

        ProbeSession(Mode mode) {
            this.mode = mode;
        }

        void awaitFirstSave() throws InterruptedException {
            assertThat(firstSave.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        void awaitSecondLockAttempt() throws InterruptedException {
            assertThat(secondLockAttempt.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        void awaitProcessing() throws InterruptedException {
            assertThat(processing.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    static final class ConcurrencyProbe {
        private final AtomicReference<ProbeSession> active = new AtomicReference<>();

        ProbeSession begin(Mode mode) {
            ProbeSession session = new ProbeSession(mode);
            assertThat(active.compareAndSet(null, session)).isTrue();
            return session;
        }

        ProbeSession session() {
            return active.get();
        }

        void clear() {
            active.set(null);
        }
    }

    @Aspect
    static final class PersistenceProbeAspect {
        private final ConcurrencyProbe probe;
        private final EntityManagerFactory entityManagerFactory;

        PersistenceProbeAspect(ConcurrencyProbe probe, EntityManagerFactory entityManagerFactory) {
            this.probe = probe;
            this.entityManagerFactory = entityManagerFactory;
        }

        @Around("bean(springAiDocumentProcessingService) && execution(* readAndSplit(..))")
        Object observeDocumentProcessing(ProceedingJoinPoint invocation) throws Throwable {
            ProbeSession session = probe.session();
            if (session != null) {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    session.transactionObservedDuringProcessing.set(true);
                }
                if (session.mode == Mode.PAUSE_DURING_PARSING
                        && Thread.currentThread().getName().equals("ingest-first")) {
                    session.processing.countDown();
                    await(session.releaseProcessing);
                }
            }
            return invocation.proceed();
        }

        @Around("bean(documentMetadataRepository) && execution(* findByIdForUpdate(..))")
        Object observeLockAttempt(ProceedingJoinPoint invocation) throws Throwable {
            ProbeSession session = probe.session();
            if (session != null) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                EntityManager context = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
                assertThat(context).isNotNull();
                if (Thread.currentThread().getName().equals("ingest-first")) {
                    session.firstPersistenceContext.compareAndSet(null, context);
                } else if (Thread.currentThread().getName().equals("ingest-second")) {
                    session.secondPersistenceContext.compareAndSet(null, context);
                    session.secondLockAttempt.countDown();
                }
            }
            return invocation.proceed();
        }

        @Around("bean(documentChunkRepository) && execution(* saveAll(..))")
        Object controlSave(ProceedingJoinPoint invocation) throws Throwable {
            ProbeSession session = probe.session();
            if (session == null) {
                return invocation.proceed();
            }
            if (Thread.currentThread().getName().equals("ingest-first")
                    && session.mode != Mode.PAUSE_DURING_PARSING) {
                session.firstSave.countDown();
                await(session.releaseFirstSave);
            }
            Object result = invocation.proceed();
            if (Thread.currentThread().getName().equals("ingest-second")
                    && session.mode == Mode.FAIL_SECOND_REPLACEMENT) {
                throw new IllegalStateException("controlled second replacement failure");
            }
            return result;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for controlled concurrency release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while controlling concurrency", exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class ConcurrencyConfiguration {
        @Bean
        ConcurrencyProbe concurrencyProbe() {
            return new ConcurrencyProbe();
        }

        @Bean
        PersistenceProbeAspect persistenceProbeAspect(ConcurrencyProbe probe, EntityManagerFactory entityManagerFactory) {
            return new PersistenceProbeAspect(probe, entityManagerFactory);
        }
    }
}

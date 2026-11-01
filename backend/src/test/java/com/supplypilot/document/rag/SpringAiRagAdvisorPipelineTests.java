package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.supplypilot.ai.config.AiProperties;
import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.semantic.SemanticProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class SpringAiRagAdvisorPipelineTests {
    private static final String QUESTION = "SUP-001 的 MAT-001 標準交期是多少？";
    private static final String EXCERPT = "SUP-001 的 MAT-001 標準交期為 5 個日曆日。";
    private static final String ANSWER = "依文件，MAT-001 標準交期為 5 個日曆日。";

    @Test
    void advisorPassesRetrievedCandidatesToModelAndReturnsTheirSources() {
        Document retrieved = retrievedDocument(EXCERPT, 0.94);
        PgVectorStore vectorStore = mock(PgVectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(retrieved));

        AtomicReference<Prompt> modelPrompt = new AtomicReference<>();
        ChatModel model = fakeModel(ANSWER, modelPrompt);
        RetrievalAugmentationAdvisor advisor = advisor(vectorStore);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SpringAiRagAnswerPipeline pipeline = pipeline(model, advisor, executor);

            SpringAiRagAnswerPipeline.QueryResult result = pipeline.answer(QUESTION);

            assertThat(result.answered()).isTrue();
            assertThat(result.answer()).isEqualTo(ANSWER);
            assertThat(result.evidence()).singleElement().satisfies(source -> {
                assertThat(source.documentId()).isEqualTo(41L);
                assertThat(source.documentKey()).isEqualTo("DOC-TEST-041");
                assertThat(source.documentType()).isEqualTo(DocumentType.SUPPLIER_CONTRACT);
                assertThat(source.title()).isEqualTo("SUP-001 供貨協議");
                assertThat(source.supplierCode()).isEqualTo("SUP-001");
                assertThat(source.excerpt()).isEqualTo(EXCERPT);
                assertThat(source.relevanceScore()).isEqualTo(0.94);
            });
            assertThat(modelPrompt.get()).isNotNull();
            assertThat(modelPrompt.get().getContents()).contains(QUESTION, EXCERPT);

            org.mockito.ArgumentCaptor<SearchRequest> request = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
            verify(vectorStore).similaritySearch(request.capture());
            assertThat(request.getValue().getQuery()).isEqualTo(QUESTION);
            assertThat(request.getValue().getTopK()).isEqualTo(10);
            assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.4);
        }
    }

    @Test
    void advisorAppliesSupplierScopeToDemoProfileRetrieval() throws IOException {
        String demoProfile = readDemoSupplierProfile();
        assertThat(demoProfile).contains("服務時段：週一至週五 09:00–17:00");
        PgVectorStore vectorStore = mock(PgVectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(retrievedDocument(demoProfile, 0.67, "SUP-003")));

        AtomicReference<Prompt> modelPrompt = new AtomicReference<>();
        ChatModel model = fakeModel("SUP-003 服務時段為週一至週五 09:00–17:00。", modelPrompt);
        RetrievalAugmentationAdvisor advisor = advisor(vectorStore);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SpringAiRagAnswerPipeline pipeline = pipeline(model, advisor, executor);

            var result = pipeline.answer("Sup-003 服務時段");

            assertThat(result.answered()).isTrue();
            assertThat(result.evidence()).singleElement()
                    .extracting(VerifiedRagEvidence::supplierCode).isEqualTo("SUP-003");
            assertThat(modelPrompt.get().getContents()).contains("週一至週五 09:00–17:00");

            org.mockito.ArgumentCaptor<SearchRequest> request = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
            verify(vectorStore).similaritySearch(request.capture());
            String filter = request.getValue().getFilterExpression().toString();
            assertThat(filter)
                    .contains("generationKey", "test-generation")
                    .contains("documentStatus", "READY")
                    .contains("outboundApprovalStatus", "APPROVED")
                    .contains("supplierCode", "SUP-003");
        }
    }

    @Test
    void emptyThresholdedRetrievalReturnsInsufficientEvidenceEvenIfModelGuesses() {
        PgVectorStore vectorStore = mock(PgVectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        AtomicReference<Prompt> modelPrompt = new AtomicReference<>();
        ChatModel model = fakeModel("SUP-001 的 MAT-001 標準交期為 999 天。", modelPrompt);
        RetrievalAugmentationAdvisor advisor = advisor(vectorStore);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SpringAiRagAnswerPipeline pipeline = pipeline(model, advisor, executor);

            SpringAiRagAnswerPipeline.QueryResult result = pipeline.answer(QUESTION);

            assertThat(result.answered()).isFalse();
            assertThat(result.answer()).isNull();
            assertThat(result.evidence()).isEmpty();
            assertThat(modelPrompt.get()).isNotNull();
            verify(vectorStore).similaritySearch(any(SearchRequest.class));
        }
    }

    @Test
    void disabledSemanticReadsReturnProviderUnavailableWithoutResolvingProviders() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        ObjectProvider<ChatModel> models = beans.getBeanProvider(ChatModel.class);
        ObjectProvider<RetrievalAugmentationAdvisor> advisors = beans.getBeanProvider(RetrievalAugmentationAdvisor.class);
        SemanticProperties properties = new SemanticProperties("jdbc:postgresql://localhost/test", "test",
                "test", "test-embedding", 3, "test-generation", false, 8, 0.4, Duration.ofSeconds(2));
        AiProperties aiProperties = new AiProperties(Duration.ofSeconds(3), 256, 3, 5, false, 1000);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SpringAiRagAnswerPipeline pipeline = new SpringAiRagAnswerPipeline(models, advisors, properties,
                    executor, aiProperties);

            assertThatThrownBy(() -> pipeline.answer(QUESTION))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(failure -> assertThat(((BusinessException) failure).getErrorCode())
                            .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        }
    }

    @Test
    void unavailableLazyAdvisorIsReportedAsProviderUnavailable() {
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<RetrievalAugmentationAdvisor> advisors = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(mock(ChatModel.class));
        when(advisors.getIfAvailable()).thenThrow(new BeanCreationException(
                "documentRagAdvisor", "PostgreSQL is temporarily unavailable"));

        SemanticProperties properties = new SemanticProperties("jdbc:postgresql://127.0.0.1:1/test", "test",
                "test", "test-embedding", 3, "test-generation", true, 8, 0.4, Duration.ofSeconds(2));
        AiProperties aiProperties = new AiProperties(Duration.ofSeconds(3), 256, 3, 5, false, 1000);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SpringAiRagAnswerPipeline pipeline = new SpringAiRagAnswerPipeline(models, advisors, properties,
                    executor, aiProperties);

            assertThatThrownBy(() -> pipeline.answer(QUESTION))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(failure -> assertThat(((BusinessException) failure).getErrorCode())
                            .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        }
    }

    private RetrievalAugmentationAdvisor advisor(PgVectorStore vectorStore) {
        return new SpringAiRagConfiguration().documentRagAdvisor(vectorStore, semanticProperties(), new AiQueryPlanner());
    }

    private SpringAiRagAnswerPipeline pipeline(ChatModel model, RetrievalAugmentationAdvisor advisor,
            ExecutorService executor) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("testChatModel", model);
        beans.addBean("testRetrievalAugmentationAdvisor", advisor);
        ObjectProvider<ChatModel> models = beans.getBeanProvider(ChatModel.class);
        ObjectProvider<RetrievalAugmentationAdvisor> advisors = beans.getBeanProvider(RetrievalAugmentationAdvisor.class);
        SemanticProperties semanticProperties = semanticProperties();
        AiProperties aiProperties = new AiProperties(Duration.ofSeconds(3), 256, 3, 5, false, 1000);
        return new SpringAiRagAnswerPipeline(models, advisors, semanticProperties, executor, aiProperties);
    }

    private SemanticProperties semanticProperties() {
        return new SemanticProperties("jdbc:postgresql://localhost/test", "test", "test",
                "test-embedding", 3, "test-generation", true, 8, 0.4, Duration.ofSeconds(2));
    }

    private ChatModel fakeModel(String answer, AtomicReference<Prompt> prompt) {
        ChatModel model = mock(ChatModel.class);
        ChatOptions options = ChatOptions.builder().build();
        when(model.getDefaultOptions()).thenReturn(options);
        when(model.getOptions()).thenReturn(options);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            prompt.set(invocation.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        });
        return model;
    }

    private Document retrievedDocument(String content, double score) {
        return retrievedDocument(content, score, "SUP-001");
    }

    private Document retrievedDocument(String content, double score, String supplierCode) {
        String title = "SUP-001".equals(supplierCode) ? "SUP-001 供貨協議" : supplierCode + " 供應商文件";
        return Document.builder().id("vector-doc-1").text(content).score(score).metadata(Map.ofEntries(
                Map.entry("documentId", 41L),
                Map.entry("documentVersion", "2.0"),
                Map.entry("generationKey", "test-generation"),
                Map.entry("documentGeneration", 1L),
                Map.entry("sourceHash", "source-hash"),
                Map.entry("chunkIndex", 0),
                Map.entry("startOffset", 0),
                Map.entry("endOffset", content.length()),
                Map.entry("documentStatus", "READY"),
                Map.entry("outboundApprovalStatus", "APPROVED"),
                Map.entry("documentType", "SUPPLIER_CONTRACT"),
                Map.entry("title", title),
                Map.entry("documentKey", "DOC-TEST-041"),
                Map.entry("supplierId", 501L),
                Map.entry("supplierCode", supplierCode),
                Map.entry("supplierName", "測試供應商"))).build();
    }

    private String readDemoSupplierProfile() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path sample = current.resolve("samples/documents/supplier-sup-003-supplier-profile-sp907-v2.txt");
            if (Files.isRegularFile(sample)) return Files.readString(sample);
            current = current.getParent();
        }
        throw new IllegalStateException("Committed SUP-003 Demo corpus file is unavailable");
    }
}

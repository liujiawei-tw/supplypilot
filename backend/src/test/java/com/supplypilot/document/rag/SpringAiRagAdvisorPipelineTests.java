package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.supplypilot.ai.config.AiProperties;
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
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class SpringAiRagAdvisorPipelineTests {
    private static final String QUESTION = "SUP-001 的 MAT-001 標準交期是多少？";
    private static final String EXCERPT = "SUP-001 的 MAT-001 標準交期為 5 個日曆日。";
    private static final String ANSWER = "SUP-999 的 MAT-999 標準交期為 999 個工作天。";

    @Test
    void advisorReturnsModelAnswerUnfilteredAndKeepsBackendCollectedEvidence() {
        RagEvidenceCollector collector = new RagEvidenceCollector();
        VerifiedRagEvidence backendEvidence = evidence();
        Document retrieved = Document.builder().id("vector-doc-1").text(EXCERPT)
                .metadata(Map.of("documentId", 41L)).score(0.94).build();
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(retrieved));

        AtomicReference<Prompt> modelPrompt = new AtomicReference<>();
        ChatModel model = fakeModel(ANSWER, modelPrompt);
        DocumentPostProcessor collectorPostProcessor = (query, documents) -> {
            assertThat(query.text()).isEqualTo(QUESTION);
            assertThat(documents).extracting(Document::getText).contains(EXCERPT);
            collector.set(List.of(backendEvidence));
            return documents;
        };
        RetrievalAugmentationAdvisor advisor = advisor(vectorStore, collectorPostProcessor);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SpringAiRagAnswerPipeline pipeline = pipeline(model, advisor, collector, executor);

            SpringAiRagAnswerPipeline.QueryResult result = pipeline.answer(QUESTION);

            assertThat(result.answered()).isTrue();
            assertThat(result.answer()).isEqualTo(ANSWER);
            assertThat(result.evidence()).containsExactly(backendEvidence);
            assertThat(modelPrompt.get()).isNotNull();
            assertThat(modelPrompt.get().getContents()).contains(QUESTION, EXCERPT);

            org.mockito.ArgumentCaptor<SearchRequest> request = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
            verify(vectorStore).similaritySearch(request.capture());
            assertThat(request.getValue().getQuery()).isEqualTo(QUESTION);
        }
    }

    @Test
    void emptyAdvisorRetrievalReturnsInsufficientEvidenceWithoutCallingChatModel() {
        RagEvidenceCollector collector = new RagEvidenceCollector();
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        AtomicReference<Prompt> modelPrompt = new AtomicReference<>();
        ChatModel model = fakeModel("SUP-001 的 MAT-001 標準交期為 999 個日曆日。", modelPrompt);
        DocumentPostProcessor failClosedPostProcessor = (query, documents) -> {
            collector.set(List.of());
            if (documents.isEmpty()) throw new NoVerifiedRagEvidenceException();
            return documents;
        };
        RetrievalAugmentationAdvisor advisor = advisor(vectorStore, failClosedPostProcessor);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SpringAiRagAnswerPipeline pipeline = pipeline(model, advisor, collector, executor);

            SpringAiRagAnswerPipeline.QueryResult result = pipeline.answer(QUESTION);

            assertThat(result.answered()).isFalse();
            assertThat(result.answer()).isNull();
            assertThat(result.evidence()).isEmpty();
            assertThat(modelPrompt.get()).isNull();
            verify(model, never()).call(any(Prompt.class));
            verify(vectorStore).similaritySearch(any(SearchRequest.class));
        }
    }

    @Test
    void disabledSemanticReadsReturnProviderUnavailableWithoutResolvingProviders() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        ObjectProvider<ChatModel> models = beans.getBeanProvider(ChatModel.class);
        ObjectProvider<RetrievalAugmentationAdvisor> advisors =
                beans.getBeanProvider(RetrievalAugmentationAdvisor.class);
        SemanticProperties properties = new SemanticProperties("jdbc:postgresql://localhost/test", "test",
                "test", "test-embedding", 3, "test-generation", false, 8, 0.45, Duration.ofSeconds(2));
        AiProperties aiProperties = new AiProperties(Duration.ofSeconds(3), 256, 3, 5, false, 1000);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SpringAiRagAnswerPipeline pipeline = new SpringAiRagAnswerPipeline(models, advisors, properties,
                    new RagEvidenceCollector(), executor,
                    aiProperties);

            assertThatThrownBy(() -> pipeline.answer(QUESTION))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(failure -> assertThat(((BusinessException) failure).getErrorCode())
                            .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        }
    }

    @Test
    void unavailableLazyAdvisorIsReportedAsProviderUnavailable() {
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<RetrievalAugmentationAdvisor> advisors =
                mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(mock(ChatModel.class));
        when(advisors.getIfAvailable()).thenThrow(new BeanCreationException(
                "documentRagAdvisor", "PostgreSQL is temporarily unavailable"));

        SemanticProperties properties = new SemanticProperties("jdbc:postgresql://127.0.0.1:1/test", "test",
                "test", "test-embedding", 3, "test-generation", true, 8, 0.45, Duration.ofSeconds(2));
        AiProperties aiProperties = new AiProperties(Duration.ofSeconds(3), 256, 3, 5, false, 1000);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SpringAiRagAnswerPipeline pipeline = new SpringAiRagAnswerPipeline(models, advisors, properties,
                    new RagEvidenceCollector(), executor,
                    aiProperties);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> pipeline.answer(QUESTION))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(failure -> assertThat(((BusinessException) failure).getErrorCode())
                            .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        }
    }

    private RetrievalAugmentationAdvisor advisor(VectorStore vectorStore,
            DocumentPostProcessor postProcessor) {
        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(8)
                .similarityThreshold(0.45)
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .documentPostProcessors(postProcessor)
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(false).build())
                .build();
    }

    private SpringAiRagAnswerPipeline pipeline(ChatModel model, RetrievalAugmentationAdvisor advisor,
            RagEvidenceCollector collector, ExecutorService executor) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("testChatModel", model);
        beans.addBean("testRetrievalAugmentationAdvisor", advisor);
        ObjectProvider<ChatModel> models = beans.getBeanProvider(ChatModel.class);
        ObjectProvider<RetrievalAugmentationAdvisor> advisors = beans.getBeanProvider(
                RetrievalAugmentationAdvisor.class);
        SemanticProperties semanticProperties = new SemanticProperties("jdbc:postgresql://localhost/test", "test",
                "test", "test-embedding", 3, "test-generation", true, 8, 0.45, Duration.ofSeconds(2));
        AiProperties aiProperties = new AiProperties(Duration.ofSeconds(3), 256, 3, 5,
                false, 1000);
        return new SpringAiRagAnswerPipeline(models, advisors, semanticProperties, collector,
                executor, aiProperties);
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

    private VerifiedRagEvidence evidence() {
        return new VerifiedRagEvidence(41L, "DOC-TEST-041", DocumentType.SUPPLIER_CONTRACT,
                "測試供應商報價", "v1", 501L, "SUP-001", "測試供應商", 0,
                0, EXCERPT.length(), EXCERPT, 0.94, false);
    }
}

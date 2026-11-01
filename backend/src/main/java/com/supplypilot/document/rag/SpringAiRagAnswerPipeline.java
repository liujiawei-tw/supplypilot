package com.supplypilot.document.rag;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.supplypilot.ai.config.AiProperties;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.semantic.SemanticProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Uses ChatClient with the single document RetrievalAugmentationAdvisor configured for this application. */
@Service
public class SpringAiRagAnswerPipeline {
    private static final String SYSTEM_PROMPT = """
            你是 SupplyPilot 文件問答助手。請使用繁體中文回答，並且只根據提供的文件 Context。
            請逐份判斷 Context 候選文件是否與問題相關，只根據能直接支持答案的內容回答。
            將回答整理成簡短、逐點、可核對的事實。不得使用模型的一般知識補充企業事實，
            不得編造來源識別值。每個數字、日期、供應商、物料或版本都必須出現在相關文件內容中。
            若沒有相關文件能支持答案，輸出 INSUFFICIENT_EVIDENCE。
            """;

    private final ObjectProvider<ChatModel> models;
    private final ObjectProvider<RetrievalAugmentationAdvisor> advisors;
    private final SemanticProperties properties;
    private final ExecutorService executor;
    private final AiProperties aiProperties;

    public SpringAiRagAnswerPipeline(ObjectProvider<ChatModel> models,
            ObjectProvider<RetrievalAugmentationAdvisor> advisors, SemanticProperties properties,
            @Qualifier("aiProviderExecutor") ExecutorService executor, AiProperties aiProperties) {
        this.models = models;
        this.advisors = advisors;
        this.properties = properties;
        this.executor = executor;
        this.aiProperties = aiProperties;
    }

    public QueryResult answer(String question) {
        if (!properties.readEnabled()) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        ChatModel model;
        RetrievalAugmentationAdvisor advisor;
        try {
            model = models.getIfAvailable();
            advisor = advisors.getIfAvailable();
        } catch (RuntimeException unavailableProvider) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        if (model == null || advisor == null) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);

        Future<GeneratedAnswer> pending = executor.submit(() -> {
            ChatClientResponse response = ChatClient.builder(model).build().prompt()
                    .system(SYSTEM_PROMPT)
                    .user(question)
                    .advisors(advisor)
                    .call()
                    .chatClientResponse();
            String generated = response.chatResponse() == null || response.chatResponse().getResult() == null
                    ? null : response.chatResponse().getResult().getOutput().getText();
            return new GeneratedAnswer(generated, retrievedEvidence(response.context()));
        });
        try {
            GeneratedAnswer result = pending.get(aiProperties.providerTimeout().toMillis(), TimeUnit.MILLISECONDS);
            String answer = result.answer() == null ? "" : result.answer().strip();
            if (answer.isBlank() || "INSUFFICIENT_EVIDENCE".equalsIgnoreCase(answer)
                    || result.evidence().isEmpty()) return QueryResult.insufficientEvidence();
            return new QueryResult(true, answer, result.evidence());
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw new BusinessException(ErrorCode.AI_TIMEOUT);
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_TIMEOUT);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof BusinessException business) throw business;
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        } catch (RuntimeException failure) {
            if (failure instanceof BusinessException business) throw business;
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    private List<VerifiedRagEvidence> retrievedEvidence(Map<String, Object> advisorContext) {
        Object retrieved = advisorContext.get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (!(retrieved instanceof List<?> documents)) return List.of();
        return documents.stream()
                .filter(Document.class::isInstance)
                .map(Document.class::cast)
                .map(this::toEvidence)
                .toList();
    }

    private VerifiedRagEvidence toEvidence(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        String type = stringValue(metadata.get("documentType"));
        String text = document.getText();
        Double score = document.getScore();
        return new VerifiedRagEvidence(longValue(metadata.get("documentId")),
                stringValue(metadata.get("documentKey")), type == null ? null : DocumentType.valueOf(type),
                stringValue(metadata.get("title")), stringValue(metadata.get("documentVersion")),
                longValue(metadata.get("supplierId")), stringValue(metadata.get("supplierCode")),
                stringValue(metadata.get("supplierName")), intValue(metadata.get("chunkIndex")),
                intValue(metadata.get("startOffset")), intValue(metadata.get("endOffset")), text,
                score == null ? 0.0 : score, metadata.containsKey("demoDatasetKey"), null, null, null,
                Set.of(), text);
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        return Long.parseLong(value.toString());
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        return Integer.parseInt(value.toString());
    }

    public record QueryResult(boolean answered, String answer, List<VerifiedRagEvidence> evidence) {
        public QueryResult {
            evidence = List.copyOf(evidence);
        }

        static QueryResult insufficientEvidence() {
            return new QueryResult(false, null, List.of());
        }
    }

    private record GeneratedAnswer(String answer, List<VerifiedRagEvidence> evidence) {
        private GeneratedAnswer {
            evidence = List.copyOf(evidence);
        }
    }
}

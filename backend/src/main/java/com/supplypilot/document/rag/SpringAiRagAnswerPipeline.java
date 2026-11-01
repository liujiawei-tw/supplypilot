package com.supplypilot.document.rag;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.supplypilot.ai.config.AiProperties;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.semantic.SemanticProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Uses ChatClient with the single document RetrievalAugmentationAdvisor configured for this application. */
@Service
public class SpringAiRagAnswerPipeline {
    private static final String SYSTEM_PROMPT = """
            你是 SupplyPilot 文件問答助手。請使用繁體中文回答，並且只根據提供的文件 Context。
            將回答整理成簡短、逐點、可核對的事實。不得使用模型的一般知識補充企業事實，
            不得編造來源識別值。每個數字、日期、供應商、物料或版本都必須出現在 Context。
            若 Context 無法支持答案，輸出 INSUFFICIENT_EVIDENCE。
            """;

    private final ObjectProvider<ChatModel> models;
    private final ObjectProvider<RetrievalAugmentationAdvisor> advisors;
    private final SemanticProperties properties;
    private final RagEvidenceCollector collector;
    private final ExecutorService executor;
    private final AiProperties aiProperties;

    public SpringAiRagAnswerPipeline(ObjectProvider<ChatModel> models,
            ObjectProvider<RetrievalAugmentationAdvisor> advisors, SemanticProperties properties,
            RagEvidenceCollector collector,
            @Qualifier("aiProviderExecutor") ExecutorService executor, AiProperties aiProperties) {
        this.models = models;
        this.advisors = advisors;
        this.properties = properties;
        this.collector = collector;
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
            collector.begin();
            try {
                String generated = ChatClient.builder(model).build().prompt()
                        .system(SYSTEM_PROMPT)
                        .user(question)
                        .advisors(advisor)
                        .call()
                        .content();
                return new GeneratedAnswer(generated, collector.get());
            } finally {
                collector.clear();
            }
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
            if (hasNoEvidenceCause(cause)) return QueryResult.insufficientEvidence();
            if (cause instanceof BusinessException business) throw business;
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        } catch (RuntimeException failure) {
            if (hasNoEvidenceCause(failure)) return QueryResult.insufficientEvidence();
            if (failure instanceof BusinessException business) throw business;
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    private boolean hasNoEvidenceCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof NoVerifiedRagEvidenceException) return true;
        }
        return false;
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

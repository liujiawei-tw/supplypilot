package com.supplypilot.ai.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.supplypilot.ai.config.AiProperties;
import com.supplypilot.ai.service.AiResponseGuard;
import com.supplypilot.ai.tool.AiToolRegistry;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.ai.tool.SpringAiReadTools;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SpringAiProviderClient implements AiProviderClient {
    private static final Logger log = LoggerFactory.getLogger(SpringAiProviderClient.class);
    private static final String SYSTEM_PROMPT = """
            You route a single SupplyPilot procurement question to exactly one provided read-only tool.
            Never answer from model knowledge. Never request a write, approval, order, receipt, inventory adjustment,
            arbitrary method, SQL, URL, or unlisted tool. Use only identifiers explicitly present in the user's message.
            The selected tool's direct result is authoritative and terminates this single-turn request.
            """;

    private final ObjectProvider<ChatModel> chatModels;
    private final AiToolRegistry toolRegistry;
    private final AiResponseGuard responseGuard;
    private final AiProperties properties;
    private final ExecutorService executor;
    private final MeterRegistry meters;

    public SpringAiProviderClient(
            ObjectProvider<ChatModel> chatModels,
            AiToolRegistry toolRegistry,
            AiResponseGuard responseGuard,
            AiProperties properties,
            ExecutorService aiProviderExecutor,
            MeterRegistry meters
    ) {
        this.chatModels = chatModels;
        this.toolRegistry = toolRegistry;
        this.responseGuard = responseGuard;
        this.properties = properties;
        this.executor = aiProviderExecutor;
        this.meters = meters;
    }

    @Override
    public AiProviderResponse query(AiProviderRequest request) {
        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            record("unavailable", Duration.ZERO, null);
            throw new AiProviderException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }

        long started = System.nanoTime();
        Future<AiProviderResponse> future = executor.submit(new DelegatingSecurityContextCallable<>(
                () -> execute(chatModel, request), SecurityContextHolder.getContext()));
        try {
            AiProviderResponse response = future.get(properties.providerTimeout().toMillis(), TimeUnit.MILLISECONDS);
            record("success", Duration.ofNanos(System.nanoTime() - started), response.totalTokens());
            return response;
        } catch (TimeoutException exception) {
            future.cancel(true);
            record("timeout", Duration.ofNanos(System.nanoTime() - started), null);
            throw new AiProviderException(ErrorCode.AI_TIMEOUT, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            record("interrupted", Duration.ofNanos(System.nanoTime() - started), null);
            throw new AiProviderException(ErrorCode.AI_TIMEOUT, exception);
        } catch (ExecutionException exception) {
            RuntimeException applicationFailure = findApplicationFailure(exception.getCause());
            if (applicationFailure != null) throw applicationFailure;
            ErrorCode code = exception.getCause() instanceof AiProviderException providerException
                    ? providerException.getErrorCode() : ErrorCode.AI_PROVIDER_UNAVAILABLE;
            record(code.name().toLowerCase(), Duration.ofNanos(System.nanoTime() - started), null);
            throw new AiProviderException(code, exception.getCause());
        }
    }

    private AiProviderResponse execute(ChatModel chatModel, AiProviderRequest request) {
        SpringAiReadTools tools = new SpringAiReadTools(toolRegistry, properties.maxToolCalls());
        ToolCallingManager manager = ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true))
                .maxCallsPerTool(properties.maxToolCalls())
                .maxTotalToolCalls(properties.maxToolCalls())
                .onLimitExceeded(ToolCallLimitBehavior.THROW)
                .build();
        ChatClient client = ChatClient.builder(chatModel)
                .defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build())
                .build();
        ChatResponse response = client.prompt()
                .system(SYSTEM_PROMPT)
                .user(request.message())
                .options(ToolCallingChatOptions.builder().maxTokens(properties.maxOutputTokens()))
                .tools(tools)
                .call()
                .chatResponse();
        if (response == null || response.getResult() == null) {
            throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE);
        }
        String payload = response.getResult().getOutput().getText();
        AiToolResult verified = responseGuard.verifyDirectToolResult(payload, tools.executions());
        Integer totalTokens = response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null : response.getMetadata().getUsage().getTotalTokens();
        return new AiProviderResponse(verified, totalTokens);
    }

    private RuntimeException findApplicationFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof BusinessException businessException) return businessException;
            if (current instanceof AccessDeniedException accessDeniedException) return accessDeniedException;
            if (current instanceof AuthenticationException authenticationException) return authenticationException;
            current = current.getCause();
        }
        return null;
    }

    private void record(String outcome, Duration elapsed, Integer totalTokens) {
        Timer.builder("supplypilot.ai.provider.latency")
                .tag("outcome", outcome).register(meters).record(elapsed);
        if (totalTokens != null) {
            meters.summary("supplypilot.ai.provider.tokens").record(totalTokens);
        }
        log.info("AI provider request outcome={} latencyMs={} totalTokens={}",
                outcome, elapsed.toMillis(), totalTokens == null ? "unavailable" : totalTokens);
    }
}

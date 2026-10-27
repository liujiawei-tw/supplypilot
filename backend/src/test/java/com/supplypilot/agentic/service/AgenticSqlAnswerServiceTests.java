package com.supplypilot.agentic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.agentic.config.AgenticSqlProperties;
import com.supplypilot.agentic.dto.AgenticSqlQueryStatus;
import com.supplypilot.agentic.security.AgenticSqlOutputPolicy;
import com.supplypilot.agentic.sql.AgenticSqlDatabase;
import com.supplypilot.agentic.sql.AgenticSqlExecutionResult;
import com.supplypilot.agentic.sql.AgenticSqlExecutor;
import com.supplypilot.agentic.sql.AgenticSqlSchemaInspector;
import com.supplypilot.agentic.sql.AgenticSqlSchemaResult;
import com.supplypilot.agentic.sql.AgenticSqlTableSchema;
import com.supplypilot.security.CurrentUserProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

class AgenticSqlAnswerServiceTests {
    private static final String SQL = "SELECT supplier_name, unit_price FROM suppliers ORDER BY unit_price";

    @Test
    void exploresSchemaThenQueriesAndReturnsEvidenceAndProviderUsage() {
        AgenticSqlExecutor executor = mock(AgenticSqlExecutor.class);
        AgenticSqlSchemaInspector inspector = mock(AgenticSqlSchemaInspector.class);
        when(inspector.inspectSchema(anyString(), eq(AgenticSqlDatabase.MYSQL), isNull(), eq("suppliers")))
                .thenReturn(new AgenticSqlSchemaResult("schema-call", AgenticSqlDatabase.MYSQL,
                        List.of(new AgenticSqlTableSchema("supplypilot", "suppliers", List.of(), List.of(),
                                List.of(), List.of(), "AUTHORITATIVE", "MYSQL", null, null))));
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("supplier_name", "Supplier A");
        row.put("unit_price", new BigDecimal("125.50"));
        row.put("api_token", "must-not-reach-model");
        when(executor.execute(anyString(), eq(AgenticSqlDatabase.MYSQL), eq(SQL)))
                .thenReturn(new AgenticSqlExecutionResult("query-call", AgenticSqlDatabase.MYSQL,
                        List.of("suppliers"), List.of("supplier_name", "unit_price", "api_token"),
                        List.of(row), 1L, false, 4, List.of()));

        AtomicInteger calls = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                int call = calls.incrementAndGet();
                if (call == 1) return toolCall("inspect_schema",
                        "{\"database\":\"MYSQL\",\"schema\":null,\"table\":\"suppliers\"}");
                if (call == 2) return toolCall("execute_readonly_sql",
                        "{\"database\":\"MYSQL\",\"sql\":\"" + SQL + "\"}");
                return response(AssistantMessage.builder().content("Supplier A 的報價為 USD 125.50。")
                        .toolCalls(List.of()).build());
            }

            @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatOptions getOptions() { return getDefaultOptions(); }
        };
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> chatModels = mock(ObjectProvider.class);
        when(chatModels.getIfAvailable()).thenReturn(chatModel);
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.getCurrentUserId()).thenReturn(42L);
        AgenticSqlProperties properties = properties();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgenticSqlOutputPolicy outputPolicy = new AgenticSqlOutputPolicy(mapper);
        var modelExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            AgenticSqlAnswerService service = new AgenticSqlAnswerService(chatModels, executor, inspector,
                    outputPolicy, properties, users, mapper, modelExecutor, new SimpleMeterRegistry());

            var result = service.query("比較供應商報價");

            assertThat(result.status()).isEqualTo(AgenticSqlQueryStatus.ANSWERED);
            assertThat(result.answer()).contains("USD 125.50");
            assertThat(result.usedDatabases()).containsExactly("MYSQL");
            assertThat(result.toolCalls()).extracting("operation")
                    .containsExactly("INSPECT_SCHEMA", "EXECUTE_READONLY_SQL");
            assertThat(result.usage().promptTokens()).isEqualTo(15);
            assertThat(result.usage().completionTokens()).isEqualTo(6);
            assertThat(result.usage().totalTokens()).isEqualTo(21);
            verify(inspector).inspectSchema(anyString(), eq(AgenticSqlDatabase.MYSQL), isNull(), eq("suppliers"));
            verify(executor).execute(anyString(), eq(AgenticSqlDatabase.MYSQL), eq(SQL));
        } finally {
            modelExecutor.close();
        }
    }

    @Test
    void refusesToReturnAProviderOnlyAnswerWithoutDatabaseEvidence() {
        ChatModel chatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                return response(new AssistantMessage("供應商甲的庫存是 500。"));
            }
            @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatOptions getOptions() { return getDefaultOptions(); }
        };
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> chatModels = mock(ObjectProvider.class);
        when(chatModels.getIfAvailable()).thenReturn(chatModel);
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.getCurrentUserId()).thenReturn(42L);
        AgenticSqlExecutor executor = mock(AgenticSqlExecutor.class);
        AgenticSqlSchemaInspector inspector = mock(AgenticSqlSchemaInspector.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var modelExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            AgenticSqlAnswerService service = new AgenticSqlAnswerService(chatModels, executor, inspector,
                    new AgenticSqlOutputPolicy(mapper), properties(), users, mapper,
                    modelExecutor, new SimpleMeterRegistry());

            var result = service.query("供應商甲的庫存是多少？");

            assertThat(result.status()).isEqualTo(AgenticSqlQueryStatus.NEEDS_CLARIFICATION);
            assertThat(result.answer()).contains("沒有取得可支持回答的資料庫查詢結果");
            assertThat(result.sources()).isEmpty();
        } finally {
            modelExecutor.close();
        }
    }

    private static ChatResponse toolCall(String name, String arguments) {
        return response(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(name, "function", name, arguments))).build());
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(5, 2, 7)).build());
    }

    private static AgenticSqlProperties properties() {
        return new AgenticSqlProperties(true, Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(2),
                8, 8, 100, 32, 12000, 1024,
                new AgenticSqlProperties.Database("jdbc:mysql://localhost/test", "agentic", "secret"),
                new AgenticSqlProperties.Database("jdbc:postgresql://localhost/test", "agentic_pg", "secret"));
    }
}

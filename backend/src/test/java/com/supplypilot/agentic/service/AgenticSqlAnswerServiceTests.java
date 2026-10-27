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
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.agentic.config.AgenticSqlProperties;
import com.supplypilot.agentic.dto.AgenticSqlQueryResponse;
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
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

class AgenticSqlAnswerServiceTests {
    private static final String SQL = "SELECT supplier_name, unit_price FROM suppliers ORDER BY unit_price";
    private static final String MYSQL_STATUS_SQL = "SELECT id FROM suppliers";
    private static final String POSTGRES_STATUS_SQL = "SELECT id FROM analytics";

    @Test
    void enablesJsonResponseModeForGoogleGenAiWithoutRelaxingAnswerVerification() {
        AtomicReference<ChatOptions> observedOptions = new AtomicReference<>();
        ChatModel googleConfiguredModel = new ChatModel() {
            private final ChatOptions defaults = GoogleGenAiChatOptions.builder()
                    .model("gemini-3.6-flash")
                    .temperature(0.2)
                    .build();

            @Override public ChatResponse call(Prompt prompt) {
                observedOptions.set(prompt.getOptions());
                return response(new AssistantMessage("{\"claims\":[]}"));
            }
            @Override public ChatOptions getDefaultOptions() { return defaults; }
            @Override public ChatOptions getOptions() { return defaults; }
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var modelExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            AgenticSqlAnswerService service = createService(googleConfiguredModel,
                    mock(AgenticSqlExecutor.class), mock(AgenticSqlSchemaInspector.class), mapper, modelExecutor);

            var result = service.query("SUP-001 供應 MAT-001 的目前單價與幣別為何？");

            assertThat(result.status()).isEqualTo(AgenticSqlQueryStatus.NEEDS_CLARIFICATION);
            assertThat(observedOptions.get()).isInstanceOf(GoogleGenAiChatOptions.class);
            GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) observedOptions.get();
            assertThat(options.getResponseMimeType()).isEqualTo("application/json");
            assertThat(options.getResponseSchema()).contains("\"claims\"");
            assertThat(options.getModel()).isEqualTo("gemini-3.6-flash");
            assertThat(options.getTemperature()).isEqualTo(0.2);
        } finally {
            modelExecutor.close();
        }
    }

    @Test
    void exploresSchemaThenQueriesAndReturnsEvidenceAndProviderUsage() {
        AgenticSqlExecutor executor = mock(AgenticSqlExecutor.class);
        AgenticSqlSchemaInspector inspector = mock(AgenticSqlSchemaInspector.class);
        when(inspector.inspectSchema(anyString(), eq(AgenticSqlDatabase.MYSQL), isNull(), eq("suppliers"), eq(0), eq(100)))
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
                        List.of(row), 1L, false, 4, List.of(), Map.of(
                                "supplier_name", Set.of("suppliers.supplier_name"),
                                "unit_price", Set.of("suppliers.unit_price"))));

        AtomicInteger calls = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                int call = calls.incrementAndGet();
                if (call == 1) return toolCall("inspect_schema",
                        "{\"database\":\"MYSQL\",\"schema\":null,\"table\":\"suppliers\"}");
                if (call == 2) return toolCall("execute_readonly_sql",
                        "{\"database\":\"MYSQL\",\"sql\":\"" + SQL + "\"}");
                String toolCallId = latestQueryToolCallId(prompt);
                String answer = "{\"claims\":[{\"text\":\"Supplier A 的報價為 USD 125.50。\","
                        + "\"fact\":{\"kind\":\"FIELD_VALUE\",\"attribute\":\"unit_price\","
                        + "\"subject\":{\"toolCallId\":\"" + toolCallId
                        + "\",\"rowIndex\":0,\"column\":\"supplier_name\"},\"value\":{\"toolCallId\":\""
                        + toolCallId + "\",\"rowIndex\":0,\"column\":\"unit_price\"}},"
                        + "\"evidence\":[{\"toolCallId\":\"" + toolCallId
                        + "\",\"rowIndex\":0,\"column\":\"supplier_name\"},{\"toolCallId\":\""
                        + toolCallId + "\",\"rowIndex\":0,\"column\":\"unit_price\"}]}]}";
                return response(AssistantMessage.builder().content(answer)
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
            verify(inspector).inspectSchema(anyString(), eq(AgenticSqlDatabase.MYSQL), isNull(),
                    eq("suppliers"), eq(0), eq(100));
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

    @Test
    void omitsAModelPriceClaimThatDoesNotMatchTheExecutedSqlValue() {
        AgenticSqlExecutor executor = mock(AgenticSqlExecutor.class);
        AgenticSqlSchemaInspector inspector = mock(AgenticSqlSchemaInspector.class);
        when(inspector.inspectSchema(anyString(), eq(AgenticSqlDatabase.MYSQL), isNull(), eq("suppliers"), eq(0), eq(100)))
                .thenReturn(new AgenticSqlSchemaResult("schema-call", AgenticSqlDatabase.MYSQL,
                        List.of(new AgenticSqlTableSchema("supplypilot", "suppliers", List.of(), List.of(),
                                List.of(), List.of(), "AUTHORITATIVE", "MYSQL", null, null))));
        when(executor.execute(anyString(), eq(AgenticSqlDatabase.MYSQL), eq(SQL)))
                .thenReturn(new AgenticSqlExecutionResult("query-call", AgenticSqlDatabase.MYSQL,
                        List.of("suppliers"), List.of("supplier_name", "unit_price"),
                        List.of(Map.of("supplier_name", "Supplier A", "unit_price", new BigDecimal("125.50"))),
                        1L, false, 4, List.of(), Map.of(
                                "supplier_name", Set.of("suppliers.supplier_name"),
                                "unit_price", Set.of("suppliers.unit_price"))));
        AtomicInteger calls = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                int call = calls.incrementAndGet();
                if (call == 1) return toolCall("inspect_schema",
                        "{\"database\":\"MYSQL\",\"schema\":null,\"table\":\"suppliers\"}");
                if (call == 2) return toolCall("execute_readonly_sql",
                        "{\"database\":\"MYSQL\",\"sql\":\"" + SQL + "\"}");
                String id = latestQueryToolCallId(prompt);
                String answer = "{\"claims\":[{\"text\":\"Supplier A 的報價為 USD 152.50。\","
                        + "\"fact\":{\"kind\":\"FIELD_VALUE\",\"attribute\":\"unit_price\","
                        + "\"subject\":{\"toolCallId\":\"" + id
                        + "\",\"rowIndex\":0,\"column\":\"supplier_name\"},\"value\":{\"toolCallId\":\""
                        + id + "\",\"rowIndex\":0,\"column\":\"unit_price\"}},"
                        + "\"evidence\":[{\"toolCallId\":\"" + id
                        + "\",\"rowIndex\":0,\"column\":\"supplier_name\"},{\"toolCallId\":\""
                        + id + "\",\"rowIndex\":0,\"column\":\"unit_price\"}]}]}";
                return response(new AssistantMessage(answer));
            }
            @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatOptions getOptions() { return getDefaultOptions(); }
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var modelExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            AgenticSqlAnswerService service = createService(chatModel, executor, inspector, mapper, modelExecutor);
            var result = service.query("比較供應商報價");

            assertThat(result.status()).isEqualTo(AgenticSqlQueryStatus.PARTIAL);
            assertThat(result.answer()).doesNotContain("152.50").contains("缺乏 SQL 結果支持");
            assertThat(result.warnings()).contains("UNSUPPORTED_MODEL_CLAIMS");
            assertThat(result.warnings()).contains("MODEL_CLAIM_FACTS_MISMATCH", "UNSUPPORTED_MODEL_FACT");
        } finally {
            modelExecutor.close();
        }
    }

    @Test
    void distinguishesNoDataFromPartialOrTotalDatabaseFailure() {
        assertThat(runDatabasePair(false, false).status()).isEqualTo(AgenticSqlQueryStatus.NO_DATA);
        assertThat(runDatabasePair(true, false).status()).isEqualTo(AgenticSqlQueryStatus.PARTIAL);
        assertThat(runDatabasePair(false, true).status()).isEqualTo(AgenticSqlQueryStatus.PARTIAL);
        var bothFailed = runDatabasePair(true, true);
        assertThat(bothFailed.status()).isEqualTo(AgenticSqlQueryStatus.QUERY_FAILED);
        assertThat(bothFailed.answer()).contains("無法判定是否存在");
    }

    private static AgenticSqlQueryResponse runDatabasePair(boolean mysqlFails, boolean postgresFails) {
        AgenticSqlExecutor executor = mock(AgenticSqlExecutor.class);
        AgenticSqlSchemaInspector inspector = mock(AgenticSqlSchemaInspector.class);
        when(inspector.inspectSchema(anyString(), eq(AgenticSqlDatabase.MYSQL), isNull(), eq("suppliers"), eq(0), eq(100)))
                .thenReturn(new AgenticSqlSchemaResult("mysql-schema", AgenticSqlDatabase.MYSQL, List.of()));
        when(inspector.inspectSchema(anyString(), eq(AgenticSqlDatabase.POSTGRESQL), eq("public"), eq("analytics"), eq(0), eq(100)))
                .thenReturn(new AgenticSqlSchemaResult("pg-schema", AgenticSqlDatabase.POSTGRESQL, List.of()));
        if (mysqlFails) {
            when(executor.execute(anyString(), eq(AgenticSqlDatabase.MYSQL), eq(MYSQL_STATUS_SQL)))
                    .thenThrow(new com.supplypilot.agentic.sql.AgenticSqlException(
                            com.supplypilot.agentic.sql.AgenticSqlException.Code.DATABASE_UNAVAILABLE));
        } else {
            when(executor.execute(anyString(), eq(AgenticSqlDatabase.MYSQL), eq(MYSQL_STATUS_SQL)))
                    .thenReturn(emptyResult(AgenticSqlDatabase.MYSQL, "suppliers"));
        }
        if (postgresFails) {
            when(executor.execute(anyString(), eq(AgenticSqlDatabase.POSTGRESQL), eq(POSTGRES_STATUS_SQL)))
                    .thenThrow(new com.supplypilot.agentic.sql.AgenticSqlException(
                            com.supplypilot.agentic.sql.AgenticSqlException.Code.DATABASE_UNAVAILABLE));
        } else {
            when(executor.execute(anyString(), eq(AgenticSqlDatabase.POSTGRESQL), eq(POSTGRES_STATUS_SQL)))
                    .thenReturn(emptyResult(AgenticSqlDatabase.POSTGRESQL, "analytics"));
        }
        ChatModel model = new ChatModel() {
            private final AtomicInteger calls = new AtomicInteger();
            @Override public ChatResponse call(Prompt prompt) {
                return switch (calls.incrementAndGet()) {
                    case 1 -> toolCall("inspect_schema",
                            "{\"database\":\"MYSQL\",\"table\":\"suppliers\"}");
                    case 2 -> toolCall("execute_readonly_sql",
                            "{\"database\":\"MYSQL\",\"sql\":\"" + MYSQL_STATUS_SQL + "\"}");
                    case 3 -> toolCall("inspect_schema",
                            "{\"database\":\"POSTGRESQL\",\"schema\":\"public\",\"table\":\"analytics\"}");
                    case 4 -> toolCall("execute_readonly_sql",
                            "{\"database\":\"POSTGRESQL\",\"sql\":\"" + POSTGRES_STATUS_SQL + "\"}");
                    default -> response(new AssistantMessage("資料不存在"));
                };
            }
            @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatOptions getOptions() { return getDefaultOptions(); }
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var modelExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            return createService(model, executor, inspector, mapper, modelExecutor).query("跨資料庫查詢");
        } finally {
            modelExecutor.close();
        }
    }

    private static AgenticSqlExecutionResult emptyResult(AgenticSqlDatabase database, String table) {
        return new AgenticSqlExecutionResult("query", database, List.of(table), List.of("id"),
                List.of(), 0L, false, 2, List.of());
    }

    private static AgenticSqlAnswerService createService(ChatModel chatModel, AgenticSqlExecutor executor,
                                                          AgenticSqlSchemaInspector inspector, ObjectMapper mapper,
                                                          java.util.concurrent.ExecutorService modelExecutor) {
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> chatModels = mock(ObjectProvider.class);
        when(chatModels.getIfAvailable()).thenReturn(chatModel);
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.getCurrentUserId()).thenReturn(42L);
        return new AgenticSqlAnswerService(chatModels, executor, inspector, new AgenticSqlOutputPolicy(mapper),
                properties(), users, mapper, modelExecutor, new SimpleMeterRegistry());
    }

    private static ChatResponse toolCall(String name, String arguments) {
        return response(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(name, "function", name, arguments))).build());
    }

    private static String latestQueryToolCallId(Prompt prompt) {
        ObjectMapper mapper = new ObjectMapper();
        for (var message : prompt.getInstructions()) {
            if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage toolResponse) {
                List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> responses =
                        toolResponse.getResponses();
                for (int index = responses.size() - 1; index >= 0; index--) {
                    try {
                        var payload = mapper.readTree(responses.get(index).responseData());
                        if (payload != null && payload.has("rows") && payload.hasNonNull("toolCallId")) {
                            return payload.get("toolCallId").asText();
                        }
                    } catch (Exception ignored) {
                        // Earlier tool responses such as schema JSON are not SQL evidence.
                    }
                }
            }
        }
        throw new IllegalStateException("Tool result did not include an evidence call id: " + prompt);
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

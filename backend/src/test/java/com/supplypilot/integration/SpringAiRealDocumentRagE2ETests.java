package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.TestcontainersConfiguration;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.SemanticBackfillService;
import com.supplypilot.document.semantic.SemanticIndexJobRepository;
import com.supplypilot.document.semantic.SemanticIndexJobStatus;
import com.supplypilot.document.semantic.SemanticIndexWorker;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.semantic.SemanticStore;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.testsupport.FixedClockConfiguration;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Opt-in real Gemini end-to-end smoke. It indexes synthetic text in isolated Testcontainers databases. */
@SpringBootTest(properties = {
        "spring.ai.model.chat=google-genai",
        "spring.ai.model.embedding.text=google-genai",
        "supplypilot.semantic.read-enabled=true",
        "supplypilot.semantic.indexing.worker-enabled=false",
        "supplypilot.semantic.embedding-timeout=PT90S",
        "supplypilot.ai.provider-timeout=PT120S"
})
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named = "semantic.real-rag-smoke", matches = "true")
class SpringAiRealDocumentRagE2ETests {
    private static final Pattern SEVEN_CALENDAR_DAYS_EQUIVALENT = Pattern.compile(
            "(?i)(?:\\b(?:7|seven)(?:\\s+calendar)?\\s+days?\\b|"
                    + "\\b(?:a|one|1)(?:\\s+calendar)?\\s+week\\b|"
                    + "[七7]\\s*(?:個)?(?:日曆日|天|日)|一(?:個)?(?:週|星期))");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Container
    static final PostgreSQLContainer<?> CONTAINER = POSTGRES;

    @DynamicPropertySource
    static void semanticProperties(DynamicPropertyRegistry registry) {
        registry.add("supplypilot.semantic.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("supplypilot.semantic.username", POSTGRES::getUsername);
        registry.add("supplypilot.semantic.password", POSTGRES::getPassword);
        registry.add("supplypilot.semantic.read-enabled", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @Autowired DocumentMetadataRepository documents;
    @Autowired DocumentChunkRepository chunks;
    @Autowired JwtTokenService tokens;
    @Autowired java.time.Clock clock;
    @Autowired SemanticIndexWorker worker;
    @Autowired SemanticIndexJobRepository jobs;
    @Autowired SemanticProperties semanticProperties;
    @Autowired SemanticStore semanticStore;
    @Autowired @Qualifier("ragQueryVectorStore") PgVectorStore queryVectorStore;

    @Test
    void realGeminiIndexesRetrievesAndAnswersAnApprovedSyntheticDocumentQuestion() throws Exception {
        assertThat(System.getenv("GEMINI_API_KEY")).isNotBlank();
        User admin = users.saveAndFlush(User.create("rag-real-" + UUID.randomUUID(), "hash",
                "RAG real provider test", UserRole.ADMIN, clock.instant()));
        String title = "RAG-REAL-" + UUID.randomUUID();
        String content = "SUP-001 MAT-001 標準交期為 7 個日曆日，自採購單進入 ORDERED 狀態後起算。";
        DocumentMetadata document = DocumentMetadata.processing(DocumentType.PROCUREMENT_SOP, null,
                title, "v1", "qa/" + title + ".txt", admin, clock.instant());
        document.markReady(clock.instant());
        document = documents.saveAndFlush(document);
        chunks.saveAndFlush(DocumentChunk.create(document, 0, 0, content.length(), content, "[]", clock.instant()));

        String bearer = bearer(admin);
        MvcResult approval = mvc.perform(post("/api/v1/semantic-index/documents/{id}/approve-outbound", document.getId())
                        .header("Authorization", bearer))
                .andReturn();
        assertThat(approval.getResponse().getStatus()).isEqualTo(200);
        assertThat(worker.processNext()).isTrue();

        String sourceHash = SemanticBackfillService.sourceHash(
                chunks.findByDocumentIdOrderByChunkIndex(document.getId()));
        DocumentMetadata indexed = documents.findById(document.getId()).orElseThrow();
        assertThat(jobs.findCurrent(document.getId(), indexed.getVersion(), sourceHash,
                semanticProperties.generationKey(), indexed.getSemanticIndexGeneration())
                .orElseThrow().status()).isEqualTo(SemanticIndexJobStatus.SUCCEEDED);
        assertThat(semanticStore.state(document.getId(), document.getVersion()).status()).isEqualTo("READY");

        String question = "SUP-001 的 MAT-001 標準交期為多久？";
        var filters = new FilterExpressionBuilder();
        var approvedGeneration = filters.and(
                filters.and(filters.eq("generationKey", semanticProperties.generationKey()),
                        filters.eq("documentStatus", "READY")),
                filters.eq("outboundApprovalStatus", "APPROVED")).build();
        var retrieved = queryVectorStore.similaritySearch(SearchRequest.builder()
                .query(question).topK(10).similarityThreshold(semanticProperties.similarityThreshold())
                .filterExpression(approvedGeneration).build());
        assertThat(retrieved).extracting(Document::getText).contains(content);

        String paraphrasedQuestion = "採購單進入 ORDERED 後，SUP-001 的 MAT-001 要等多久交貨？";
        String unsupportedQuestion = "SUP-001 的 MAT-001 單價是多少？";
        JsonNode firstResponse = ask(bearer, question);
        JsonNode paraphrasedResponse = ask(bearer, paraphrasedQuestion);
        JsonNode unsupportedResponse = ask(bearer, unsupportedQuestion);

        printResult(question, firstResponse);
        printResult(paraphrasedQuestion, paraphrasedResponse);
        printResult(unsupportedQuestion, unsupportedResponse);
        assertSupportedAnswer(firstResponse, question, document.getId());
        assertSupportedAnswer(paraphrasedResponse, paraphrasedQuestion, document.getId());
        assertThat(unsupportedResponse.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(unsupportedResponse.path("answer").isNull()).isTrue();
        assertThat(unsupportedResponse.path("sources")).isEmpty();
    }

    private void assertSupportedAnswer(JsonNode response, String question, long expectedDocumentId) {
        assertThat(response.path("status").asText()).isEqualTo("ANSWERED");
        String answer = response.path("answer").asText();
        assertThat(SEVEN_CALENDAR_DAYS_EQUIVALENT.matcher(answer).find()).isTrue();
        assertThat(answer.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("business day", "working day", "work day");
        assertThat(answer).doesNotContain("工作天", "工作日");
        assertThat(response.path("sources")).hasSize(1);
        assertThat(response.path("sources").get(0).path("documentId").asLong()).isEqualTo(expectedDocumentId);
        assertThat(response.path("sources").get(0).path("excerpt").asText()).contains("7 個日曆日");
    }

    private JsonNode ask(String bearer, String question) throws Exception {
        MvcResult query = mvc.perform(post("/api/v1/document-queries")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("question", question))))
                .andReturn();
        assertThat(query.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(query.getResponse().getContentAsString());
    }

    private void printResult(String question, JsonNode response) {
        System.out.printf("REAL_GEMINI_RAG_QA question=%s status=%s answer=%s sources=%s%n",
                question, response.path("status").asText(), response.path("answer").asText("<none>"),
                response.path("sources").size());
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(
                new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }
}

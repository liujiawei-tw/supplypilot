package com.supplypilot.integration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.SemanticBackfillService;
import com.supplypilot.document.semantic.DocumentSemanticLifecycleReader;
import com.supplypilot.document.semantic.SemanticIndexWorker;
import com.supplypilot.document.semantic.SemanticProperties;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "supplypilot.semantic.read-enabled=true",
        "supplypilot.semantic.indexing.worker-enabled=false",
        "spring.ai.model.embedding.text=google-genai",
        "spring.ai.google.genai.api-key=test-only-no-network-call"
})
@AutoConfigureMockMvc
@Import(DocumentAnswerProviderFailureIntegrationTests.ProviderConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class DocumentAnswerProviderFailureIntegrationTests extends AbstractIntegrationTest {
    private static final String SEMANTIC_DATABASE = "supplypilot_answer_failure_test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(SEMANTIC_DATABASE);

    @DynamicPropertySource
    static void semanticPostgres(DynamicPropertyRegistry registry) {
        registry.add("supplypilot.semantic.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("supplypilot.semantic.username", POSTGRES::getUsername);
        registry.add("supplypilot.semantic.password", POSTGRES::getPassword);
    }


    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private DocumentMetadataRepository documents;
    @Autowired private DocumentChunkRepository chunks;
    @Autowired private JwtTokenService tokens;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private FailingAnswerProvider failingAnswerProvider;
    @Autowired private DocumentSemanticLifecycleReader lifecycle;
    @Autowired private SemanticIndexWorker indexWorker;

    @BeforeEach
    void cleanDocuments() {
        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
        failingAnswerProvider.reset();
    }

    @Test
    void answerProviderFailureReturnsServiceUnavailableErrorCode() throws Exception {
        User admin = user();
        long documentId = uploadTxt(admin, "Supplier payment terms are net 30 days.");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                .header("Authorization", bearer(admin))).andExpect(status().isOk());

        var document = documents.findById(documentId).orElseThrow();
        var source = chunks.findByDocumentIdOrderByChunkIndex(documentId);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(source).hasSize(1);
        assertThat(source.getFirst().getContent()).isEqualTo("Supplier payment terms are net 30 days.");

        MvcResult approvalResult = mockMvc.perform(post(
                        "/api/v1/semantic-index/documents/{id}/approve-outbound", documentId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v7"))
                .andExpect(jsonPath("$.approvedBy").value(admin.getId()))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn();
        String approvedHash = objectMapper.readTree(approvalResult.getResponse().getContentAsString())
                .path("sourceHash").asText();
        assertThat(approvedHash).matches("[0-9a-f]{64}")
                .isEqualTo(SemanticBackfillService.sourceHash(source));
        document = documents.findById(documentId).orElseThrow();
        assertThat(document.getSemanticReviewStatus()).isEqualTo(SemanticReviewStatus.APPROVED);
        assertThat(document.getSemanticReviewVersion()).isEqualTo("v7");
        assertThat(document.getSemanticApprovedSourceHash()).isEqualTo(approvedHash);

        assertThat(indexWorker.processNext()).isTrue();
        assertThat(lifecycle.read(document).status()).isEqualTo("INDEXED");

        mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"supplier payment terms\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
        assertThat(failingAnswerProvider.calls()).isEqualTo(1);
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
        return users.saveAndFlush(User.create("answer-fault-" + suffix,
                "hash", "Answer Fault User", UserRole.ADMIN, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean
        @Primary
        FailingAnswerProvider failingAnswerProvider() {
            return new FailingAnswerProvider();
        }

        @Bean(name = "ragDocumentEmbeddingModel")
        @Primary
        EmbeddingModel testDocumentEmbeddingModel(SemanticProperties properties) {
            return testEmbeddingModel(properties);
        }

        @Bean(name = "ragQueryEmbeddingModel")
        EmbeddingModel testQueryEmbeddingModel(SemanticProperties properties) {
            return testEmbeddingModel(properties);
        }

        private EmbeddingModel testEmbeddingModel(SemanticProperties properties) {
            return new EmbeddingModel() {
                @Override
                public EmbeddingResponse call(EmbeddingRequest request) {
                    return new EmbeddingResponse(request.getInstructions().stream()
                            .map(text -> new Embedding(vector(properties.dimensions()), 0)).toList());
                }

                @Override
                public float[] embed(Document document) {
                    return vector(properties.dimensions());
                }
            };
        }

        private static float[] vector(int dimensions) {
            float[] values = new float[dimensions];
            values[0] = 1.0f;
            return values;
        }
    }

    static class FailingAnswerProvider implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            throw new IllegalStateException("answer unavailable");
        }

        void reset() {
            calls.set(0);
        }

        int calls() {
            return calls.get();
        }

    }
}

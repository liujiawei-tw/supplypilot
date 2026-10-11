package com.supplypilot.integration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.rag.AnswerProvider;
import com.supplypilot.document.rag.EmbeddingProvider;
import com.supplypilot.document.rag.LocalLexicalEmbeddingProvider;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(DocumentRagIntegrationTests.ProviderConfiguration.class)
class DocumentRagIntegrationTests extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private DocumentMetadataRepository documents;
    @Autowired private DocumentChunkRepository chunks;
    @Autowired private JwtTokenService tokens;
    @Autowired private Clock clock;
    @Autowired private ControllableEmbeddingProvider embeddings;
    @Autowired private ControllableAnswerProvider answers;

    @BeforeEach
    void resetProviders() {
        embeddings.fail.set(false);
        answers.fail.set(false);
        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
    }

    @Test
    void ingestsQueriesWithPersistedSourceAndReingestsWithoutDuplicateChunks() throws Exception {
        User staff = user(UserRole.PROCUREMENT_STAFF);
        long documentId = uploadTxt(staff, "Supplier payment terms are net 30 days. Contract renewal is annual.");

        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        assertThat(chunks.countByDocumentId(documentId)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/document-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What are the supplier payment terms?\"}")
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answer").value("Grounded answer from retrieved evidence."))
                .andExpect(jsonPath("$.sources[0].documentId").value(documentId))
                .andExpect(jsonPath("$.sources[0].documentType").value("SUPPLIER_CONTRACT"))
                .andExpect(jsonPath("$.sources[0].title").value("Supplier Contract"))
                .andExpect(jsonPath("$.sources[0].version").value("v7"))
                .andExpect(jsonPath("$.sources[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("net 30")));

        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        assertThat(chunks.countByDocumentId(documentId)).isEqualTo(1);
    }

    @Test
    void returnsInsufficientEvidenceForNoHitsAndWeakHits() throws Exception {
        User admin = user(UserRole.ADMIN);
        long documentId = uploadTxt(admin, "Supplier payment terms are net 30 days.");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                .header("Authorization", bearer(admin))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"warehouse forklift capacity\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.sources").isEmpty());

        mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"supplier forklift\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"));
    }

    @Test
    void parsingAndEmbeddingFailuresNeverProduceUsableReadyIndex() throws Exception {
        User admin = user(UserRole.ADMIN);
        long blankId = uploadTxt(admin, "   \n   ");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", blankId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(chunks.countByDocumentId(blankId)).isZero();

        long providerFailureId = uploadTxt(admin, "Supplier payment terms are net 30 days.");
        embeddings.fail.set(true);
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", providerFailureId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
        assertThat(documents.findById(providerFailureId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(chunks.countByDocumentId(providerFailureId)).isZero();
    }

    @Test
    void answerProviderFailureIsNotInsufficientEvidence() throws Exception {
        User admin = user(UserRole.ADMIN);
        long documentId = uploadTxt(admin, "Supplier payment terms are net 30 days.");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
        answers.fail.set(true);

        mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"supplier payment terms\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void inactiveAndProcessingDocumentsAreExcludedFromRetrieval() throws Exception {
        User admin = user(UserRole.ADMIN);
        long processingId = uploadTxt(admin, "warehouse forklift capacity is 500 kilograms");
        mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"warehouse forklift capacity\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"));

        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", processingId)
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/v1/documents/{id}", processingId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}")
                        .header("Authorization", bearer(admin))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", processingId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(documents.findById(processingId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.INACTIVE);
        mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"warehouse forklift capacity\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"));
    }

    @Test
    void enforcesIngestionAndQueryRoleMatrices() throws Exception {
        User admin = user(UserRole.ADMIN);
        long documentId = uploadTxt(admin, "Supplier payment terms are net 30 days.");
        for (UserRole denied : List.of(UserRole.PRODUCTION_PLANNER, UserRole.PROCUREMENT_MANAGER, UserRole.WAREHOUSE_STAFF)) {
            mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                            .header("Authorization", bearer(user(denied))))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId))
                .andExpect(status().isUnauthorized());

        for (UserRole allowed : List.of(UserRole.PROCUREMENT_STAFF, UserRole.PROCUREMENT_MANAGER, UserRole.ADMIN)) {
            mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"unindexed evidence\"}")
                            .header("Authorization", bearer(user(allowed))))
                    .andExpect(status().isOk());
        }
        for (UserRole denied : List.of(UserRole.PRODUCTION_PLANNER, UserRole.WAREHOUSE_STAFF)) {
            mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"unindexed evidence\"}")
                            .header("Authorization", bearer(user(denied))))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"unindexed evidence\"}"))
                .andExpect(status().isUnauthorized());
    }

    private long uploadTxt(User actor, String text) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "contract.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
        String response = mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .param("documentType", "SUPPLIER_CONTRACT")
                        .param("title", "Supplier Contract").param("version", "v7")
                        .header("Authorization", bearer(actor)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("id").asLong();
    }

    private User user(UserRole role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return users.saveAndFlush(User.create("rag-" + role.name().toLowerCase() + "-" + suffix,
                "hash", "RAG User", role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean
        @Primary
        ControllableEmbeddingProvider testEmbeddingProvider() {
            return new ControllableEmbeddingProvider();
        }

        @Bean
        @Primary
        ControllableAnswerProvider testAnswerProvider() {
            return new ControllableAnswerProvider();
        }
    }

    static class ControllableEmbeddingProvider implements EmbeddingProvider {
        final AtomicBoolean fail = new AtomicBoolean();
        private final LocalLexicalEmbeddingProvider delegate = new LocalLexicalEmbeddingProvider();

        @Override
        public List<Double> embed(String text) {
            if (fail.get()) {
                throw new IllegalStateException("embedding unavailable");
            }
            return delegate.embed(text);
        }
    }

    static class ControllableAnswerProvider implements AnswerProvider {
        final AtomicBoolean fail = new AtomicBoolean();

        @Override
        public String answer(String question, List<com.supplypilot.document.rag.RetrievedChunk> evidence) {
            if (fail.get()) {
                throw new IllegalStateException("answer unavailable");
            }
            return "Grounded answer from retrieved evidence.";
        }
    }
}

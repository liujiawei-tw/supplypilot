package com.supplypilot.integration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
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
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ControllableEmbeddingProvider embeddings;

    @BeforeEach
    void resetProviders() {
        embeddings.fail.set(false);
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

        String queryResponse = mockMvc.perform(post("/api/v1/document-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What are the supplier payment terms?\"}")
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("net 30 days")))
                .andReturn().getResponse().getContentAsString();

        DocumentMetadata persistedDocument = documents.findById(documentId).orElseThrow();
        DocumentChunk persistedChunk = chunks.findByDocumentIdOrderByChunkIndex(documentId).getFirst();
        JsonNode response = objectMapper.readTree(queryResponse);
        JsonNode source = response.path("sources").get(0);
        assertThat(response.path("answer").asText()).isEqualTo(source.path("excerpt").asText());
        assertThat(source.path("documentId").asLong()).isEqualTo(persistedDocument.getId());
        assertThat(source.path("documentType").asText()).isEqualTo(persistedDocument.getDocumentType().name());
        assertThat(source.path("title").asText()).isEqualTo(persistedDocument.getTitle());
        assertThat(source.path("version").asText()).isEqualTo(persistedDocument.getVersion());
        assertThat(source.path("supplierId").isNull()).isTrue();
        assertThat(source.path("chunkIndex").asInt()).isEqualTo(persistedChunk.getChunkIndex());
        assertThat(source.path("startOffset").asInt()).isEqualTo(persistedChunk.getStartOffset());
        assertThat(source.path("endOffset").asInt()).isEqualTo(persistedChunk.getEndOffset());
        assertThat(source.path("excerpt").asText())
                .isEqualTo("Supplier payment terms are net 30 days.");
        assertThat(source.path("excerpt").asText()).isNotEqualTo(persistedChunk.getContent());

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

        String noHitResponse = mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"warehouse forklift capacity\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.sources").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(noHitResponse).path("answer").isNull()).isTrue();

        String weakHitResponse = mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"supplier forklift\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.sources").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(weakHitResponse).path("answer").isNull()).isTrue();

        String partialOverlapResponse = mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What are the supplier payment terms and cancellation fee?\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.sources").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(partialOverlapResponse).path("answer").isNull()).isTrue();
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
}

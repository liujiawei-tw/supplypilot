package com.supplypilot.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.rag.SpringAiDocumentProcessingService;
import com.supplypilot.document.rag.SpringAiRagAnswerPipeline;
import com.supplypilot.document.rag.VerifiedRagEvidence;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the document upload/ingestion and HTTP contract. Retrieval correctness, live authorization,
 * source validation and model-answer pass-through are exercised by the focused Spring AI unit/integration tests.
 */
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
    @Autowired private SpringAiDocumentProcessingService documentProcessing;
    @Autowired private SpringAiRagAnswerPipeline answerPipeline;

    @BeforeEach
    void resetPipelineAndDocuments() {
        reset(answerPipeline);
        when(answerPipeline.answer(anyString())).thenReturn(
                new SpringAiRagAnswerPipeline.QueryResult(false, null, List.of()));
        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
    }

    @Test
    void ingestsAndReingestsWithSpringAiChunksAndMapsVerifiedSourcesThroughTheApi() throws Exception {
        User staff = user(UserRole.PROCUREMENT_STAFF);
        String text = "Supplier payment terms are net 30 days. Contract renewal is annual.";
        long documentId = uploadTxt(staff, text);

        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        assertThat(chunks.countByDocumentId(documentId)).isEqualTo(1);
        approveOutbound(user(UserRole.ADMIN), documentId);

        DocumentMetadata persistedDocument = documents.findForSemanticRead(documentId).orElseThrow();
        DocumentChunk persistedChunk = chunks.findByDocumentIdOrderByChunkIndex(documentId).getFirst();
        String sourceText = documentProcessing.extract(persistedDocument.getStoragePath());
        when(answerPipeline.answer(anyString())).thenReturn(new SpringAiRagAnswerPipeline.QueryResult(true,
                "Supplier payment terms are net 30 days.", List.of(evidence(persistedDocument, persistedChunk, 0.91))));

        String queryResponse = mockMvc.perform(post("/api/v1/document-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What are the supplier payment terms?\"}")
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answer").value("Supplier payment terms are net 30 days."))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(queryResponse);
        JsonNode source = response.path("sources").get(0);
        assertThat(response.path("answer").asText()).isNotEqualTo(source.path("excerpt").asText());
        assertThat(source.path("documentId").asLong()).isEqualTo(persistedDocument.getId());
        assertThat(source.path("documentType").asText()).isEqualTo(persistedDocument.getDocumentType().name());
        assertThat(source.path("title").asText()).isEqualTo(persistedDocument.getTitle());
        assertThat(source.path("version").asText()).isEqualTo(persistedDocument.getVersion());
        assertThat(source.path("supplierId").isNull()).isTrue();
        assertThat(source.path("chunkIndex").asInt()).isEqualTo(persistedChunk.getChunkIndex());
        assertThat(source.path("startOffset").asInt()).isEqualTo(persistedChunk.getStartOffset());
        assertThat(source.path("endOffset").asInt()).isEqualTo(persistedChunk.getEndOffset());
        assertThat(source.path("excerpt").asText()).isEqualTo(persistedChunk.getContent());
        assertThat(source.path("excerpt").asText()).isEqualTo(sourceText.substring(
                source.path("startOffset").asInt(), source.path("endOffset").asInt()));
        verify(answerPipeline).answer("What are the supplier payment terms?");

        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                        .header("Authorization", bearer(staff)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        assertThat(chunks.countByDocumentId(documentId)).isEqualTo(1);
    }

    @Test
    void ingestsRealQualitySampleWithVerifiableSpringAiChunkOffsets() throws Exception {
        User admin = user(UserRole.ADMIN);
        String source = Files.readString(Path.of("..", "samples", "documents", "quality-requirement-v1.txt"));
        long documentId = uploadTxt(admin, source, "1.0");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));

        DocumentMetadata document = documents.findById(documentId).orElseThrow();
        String extracted = documentProcessing.extract(document.getStoragePath());
        List<DocumentChunk> persistedChunks = chunks.findByDocumentIdOrderByChunkIndex(documentId);
        assertThat(persistedChunks).isNotEmpty();
        assertThat(persistedChunks).extracting(DocumentChunk::getEmbedding).containsOnly("[]");
        for (DocumentChunk chunk : persistedChunks) {
            assertThat(chunk.getContent()).isEqualTo(extracted.substring(chunk.getStartOffset(), chunk.getEndOffset()));
        }
    }

    @Test
    void mapsMissingVerifiedContextToInsufficientEvidenceWithoutSources() throws Exception {
        User admin = user(UserRole.ADMIN);
        long documentId = uploadTxt(admin, "Supplier payment terms are net 30 days.");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        String response = mockMvc.perform(post("/api/v1/document-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"warehouse forklift capacity\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.sources").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(response).path("answer").isNull()).isTrue();
        verify(answerPipeline).answer("warehouse forklift capacity");
    }

    @Test
    void parsingFailuresNeverProduceUsableReadyIndex() throws Exception {
        User admin = user(UserRole.ADMIN);
        long blankId = uploadTxt(admin, "   \n   ");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", blankId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(chunks.countByDocumentId(blankId)).isZero();
        assertThat(documents.findById(blankId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void inactiveDocumentsCannotBeReingested() throws Exception {
        User admin = user(UserRole.ADMIN);
        long documentId = uploadTxt(admin, "warehouse forklift capacity is 500 kilograms");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        mockMvc.perform(patch("/api/v1/documents/{id}", documentId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(documents.findById(documentId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.INACTIVE);
        assertThat(chunks.countByDocumentId(documentId)).isEqualTo(1);
    }

    @Test
    void enforcesIngestionAndQueryRoleMatrices() throws Exception {
        User admin = user(UserRole.ADMIN);
        long documentId = uploadTxt(admin, "Supplier payment terms are net 30 days.");
        for (UserRole denied : List.of(UserRole.PRODUCTION_PLANNER, UserRole.PROCUREMENT_MANAGER,
                UserRole.WAREHOUSE_STAFF)) {
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

    private VerifiedRagEvidence evidence(DocumentMetadata document, DocumentChunk chunk, double score) {
        return new VerifiedRagEvidence(document.getId(), document.getDemoDocumentKey(), document.getDocumentType(),
                document.getTitle(), document.getVersion(), null, null, null, chunk.getChunkIndex(),
                chunk.getStartOffset(), chunk.getEndOffset(), chunk.getContent(), score, false);
    }

    private long uploadTxt(User actor, String text) throws Exception {
        return uploadTxt(actor, text, "v7");
    }

    private long uploadTxt(User actor, String text, String version) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "contract.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
        String response = mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .param("documentType", "SUPPLIER_CONTRACT")
                        .param("title", "Supplier Contract").param("version", version)
                        .header("Authorization", bearer(actor)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private User user(UserRole role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return users.saveAndFlush(User.create("rag-" + role.name().toLowerCase() + "-" + suffix,
                "hash", "RAG User", role, clock.instant()));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()));
    }

    private void approveOutbound(User admin, long documentId) throws Exception {
        mockMvc.perform(post("/api/v1/semantic-index/documents/{id}/approve-outbound", documentId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean
        @Primary
        SpringAiRagAnswerPipeline documentQueryPipeline() {
            return Mockito.mock(SpringAiRagAnswerPipeline.class);
        }
    }
}

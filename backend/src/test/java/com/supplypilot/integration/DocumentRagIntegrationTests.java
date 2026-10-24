package com.supplypilot.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.supplypilot.document.rag.DocumentTextExtractor;
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
    @Autowired private DocumentTextExtractor textExtractor;

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
        approveOutbound(user(UserRole.ADMIN), documentId);

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
        String sourceText = textExtractor.extract(persistedDocument.getStoragePath());
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
                source.path("startOffset").asInt(), source.path("endOffset").asInt()).trim());
        assertThat(response.path("answer").asText()).contains("net 30 days")
                .doesNotContain("Contract renewal");

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
    void realQualitySampleAnswersConditionListAndHintOnlyTextIsInsufficient() throws Exception {
        User admin = user(UserRole.ADMIN);
        String qualityText = Files.readString(Path.of("..", "samples", "documents", "quality-requirement-v1.txt"));
        long qualityId = uploadTxt(admin, qualityText, "1.0");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", qualityId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        approveOutbound(admin, qualityId);

        String answerResponse = mockMvc.perform(post("/api/v1/document-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"品質要求中哪些狀況需要隔離批次？\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("缺少批次識別")))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("封條破損")))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("受到污染")))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("物料不符")))
                .andReturn().getResponse().getContentAsString();
        JsonNode answer = objectMapper.readTree(answerResponse);
        JsonNode source = answer.path("sources").get(0);
        DocumentChunk persistedChunk = chunks.findByDocumentIdOrderByChunkIndex(qualityId).getFirst();
        assertThat(source.path("documentId").asLong()).isEqualTo(qualityId);
        assertThat(source.path("version").asText()).isEqualTo("1.0");
        assertThat(source.path("chunkIndex").asInt()).isZero();
        assertThat(source.path("startOffset").asInt()).isEqualTo(persistedChunk.getStartOffset());
        assertThat(source.path("endOffset").asInt()).isEqualTo(persistedChunk.getEndOffset());
        assertThat(source.path("endOffset").asInt()).isEqualTo(persistedChunk.getContent().length());
        assertThat(source.path("excerpt").asText()).isEqualTo(persistedChunk.getContent());

        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
        long hintId = uploadTxt(admin, "問答：哪些情況需要隔離？");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", hintId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        mockMvc.perform(post("/api/v1/document-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"哪些情況需要隔離？\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.sources").isEmpty());
    }

    @Test
    void quarantineQueryRejectsGenericTreatmentAndDoesNotBorrowAnotherConditionAction() throws Exception {
        User admin = user(UserRole.ADMIN);
        String question = "哪些情況需要隔離批次？";
        long genericId = uploadTxt(admin, "依照品質管理規定，異常批次須隔離。");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", genericId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("question", question)))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.sources").isEmpty());

        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
        String compound = "若設備停機，立即通知維修；若封條破損，須隔離批次。";
        long compoundId = uploadTxt(admin, compound);
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", compoundId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        approveOutbound(admin, compoundId);
        String response = mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("question", question)))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode answer = objectMapper.readTree(response);
        assertThat(answer.path("answer").asText()).contains("封條破損").doesNotContain("設備停機");
        assertThat(answer.path("sources").get(0).path("documentId").asLong()).isEqualTo(compoundId);
        assertThat(answer.path("sources").get(0).path("version").asText()).isEqualTo("v7");
        assertThat(answer.path("sources").get(0).path("excerpt").asText())
                .isEqualTo(chunks.findByDocumentIdOrderByChunkIndex(compoundId).getFirst().getContent());
    }

    @Test
    void quarantineQueryTreatsPolicyPreambleAndGenericAbnormalityAsInsufficientEvidence() throws Exception {
        User admin = user(UserRole.ADMIN);
        String question = "哪些情況需要隔離批次？";
        for (String content : List.of(
                "若依照品質管理規定，異常批次須隔離。",
                "若依照規定，異常批次須隔離。",
                "若有異常情況，應隔離該批次。",
                "If required by policy, quarantine abnormal batches.")) {
            long documentId = uploadTxt(admin, content);
            mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                            .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
            mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("question", question)))
                            .header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                    .andExpect(jsonPath("$.answer").doesNotExist())
                    .andExpect(jsonPath("$.sources").isEmpty());
            chunks.deleteAllInBatch();
            documents.deleteAllInBatch();
        }
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

    private void approveOutbound(User admin, long documentId) throws Exception {
        mockMvc.perform(post("/api/v1/semantic-index/documents/{id}/approve-outbound", documentId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
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

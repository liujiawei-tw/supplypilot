package com.supplypilot.integration;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.rag.LocalLexicalEmbeddingProvider;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.SemanticBackfillService;
import com.supplypilot.document.semantic.SemanticIndexJob;
import com.supplypilot.document.semantic.SemanticIndexJobRepository;
import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "supplypilot.semantic.jdbc-url=jdbc:postgresql://localhost:1/unavailable",
        "supplypilot.semantic.username=unused",
        "supplypilot.semantic.password=unused",
        "supplypilot.semantic.read-enabled=true"
})
@AutoConfigureMockMvc
class SemanticPostgresFailureIntegrationTests extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired DocumentMetadataRepository documents;
    @Autowired DocumentChunkRepository chunks;
    @Autowired SemanticIndexJobRepository indexJobs;
    @Autowired JwtTokenService tokens;
    @Autowired Clock clock;

    @Test
    void unavailablePostgresReturnsAi503WithoutLexicalFallbackOrCoreFailure() throws Exception {
        User admin = users.saveAndFlush(User.create("pg-down-" + UUID.randomUUID(), "hash",
                "Postgres outage", UserRole.ADMIN, clock.instant()));
        String bearer = "Bearer " + tokens.generateToken(
                new AuthenticatedUser(admin.getId(), admin.getUsername(), admin.getRole()));
        mvc.perform(post("/api/v1/document-queries").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"forklift capacity\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
        mvc.perform(get("/api/v1/documents").header("Authorization", bearer))
                .andExpect(status().isOk());
    }

    @Test
    void documentGetReportsUnavailableInsteadOfIndexedWhenPublicationStoreIsDown() throws Exception {
        User admin = users.saveAndFlush(User.create("pg-document-" + UUID.randomUUID(), "hash",
                "Postgres outage", UserRole.ADMIN, clock.instant()));
        DocumentMetadata document = DocumentMetadata.processing(DocumentType.PROCUREMENT_SOP, null,
                "Forklift operating procedure", "v1", "unused.txt", admin, clock.instant());
        document.markReady(clock.instant());
        document = documents.saveAndFlush(document);
        String content = "Forklift capacity checks occur before each work shift.";
        String embedding = new LocalLexicalEmbeddingProvider().embed(content).stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        chunks.saveAndFlush(DocumentChunk.create(document, 0, 0, content.length(), content,
                embedding, clock.instant()));
        String bearer = "Bearer " + tokens.generateToken(
                new AuthenticatedUser(admin.getId(), admin.getUsername(), admin.getRole()));
        mvc.perform(post("/api/v1/semantic-index/documents/{id}/approve-outbound", document.getId())
                        .header("Authorization", bearer))
                .andExpect(status().isOk());
        SemanticIndexJob claimed = indexJobs.claimNext(clock.instant(), clock.instant().plusSeconds(30), 4)
                .orElseThrow();
        assertThat(claimed.documentId()).isEqualTo(document.getId());
        assertThat(indexJobs.succeed(claimed.id(), claimed.attempts(), clock.instant())).isTrue();

        mvc.perform(get("/api/v1/documents/{id}", document.getId()).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.semanticIndex.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.semanticIndex.failureCode").value("INDEX_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.semanticIndex.sourceHash")
                        .value(SemanticBackfillService.sourceHash(List.of(
                                chunks.findByDocumentIdOrderByChunkIndex(document.getId()).getFirst()))));
    }
}

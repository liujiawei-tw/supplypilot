package com.supplypilot.integration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.document.rag.AnswerProvider;
import com.supplypilot.document.rag.RetrievedChunk;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(DocumentAnswerProviderFailureIntegrationTests.ProviderConfiguration.class)
class DocumentAnswerProviderFailureIntegrationTests extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private DocumentMetadataRepository documents;
    @Autowired private DocumentChunkRepository chunks;
    @Autowired private JwtTokenService tokens;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDocuments() {
        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
    }

    @Test
    void answerProviderFailureReturnsServiceUnavailableErrorCode() throws Exception {
        User admin = user();
        long documentId = uploadTxt(admin, "Supplier payment terms are net 30 days.");
        mockMvc.perform(post("/api/v1/documents/{id}/ingestions", documentId)
                .header("Authorization", bearer(admin))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/document-queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"supplier payment terms\"}")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
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
        AnswerProvider failingAnswerProvider() {
            return new FailingAnswerProvider();
        }
    }

    static class FailingAnswerProvider implements AnswerProvider {
        @Override
        public String answer(String question, List<RetrievedChunk> evidence) {
            throw new IllegalStateException("answer unavailable");
        }
    }
}

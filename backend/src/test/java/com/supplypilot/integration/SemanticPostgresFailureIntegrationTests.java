package com.supplypilot.integration;

import java.time.Clock;
import java.util.UUID;

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
}

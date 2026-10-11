package com.supplypilot.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.UUID;

import com.supplypilot.security.AuthenticatedUser;
import com.supplypilot.security.JwtProperties;
import com.supplypilot.security.JwtTokenService;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "spring.ai.model.chat=google-genai",
        "spring.ai.google.genai.api-key="
})
class AiMissingKeyIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private Clock clock;
    @Autowired private UserRepository users;

    @Test
    void configuredGeminiWithoutKeyStartsCoreAndReturnsAi503() throws Exception {
        User staff = users.saveAndFlush(User.create("ai-no-key-" + UUID.randomUUID(), "hash",
                UserRole.PROCUREMENT_STAFF.name(), UserRole.PROCUREMENT_STAFF, clock.instant()));
        String bearer = "Bearer " + new JwtTokenService(jwtProperties, clock).generateToken(
                new AuthenticatedUser(staff.getId(), staff.getUsername(), staff.getRole()));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();

        mvc.perform(post("/api/v1/ai/query").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"查詢 MAT-001 庫存\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
    }
}

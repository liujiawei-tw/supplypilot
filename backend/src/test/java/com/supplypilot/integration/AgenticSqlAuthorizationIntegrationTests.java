package com.supplypilot.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Verifies that an authenticated but unauthorized role cannot access the opt-in agent. */
@TestPropertySource(properties = {
        "supplypilot.agentic.sql.enabled=true",
        "supplypilot.agentic.sql.mysql.jdbc-url=jdbc:mysql://127.0.0.1:1/agentic-test",
        "supplypilot.agentic.sql.mysql.username=agentic_mysql_readonly_test",
        "supplypilot.agentic.sql.mysql.password=test-only",
        "supplypilot.agentic.sql.postgres.jdbc-url=jdbc:postgresql://127.0.0.1:1/agentic-test",
        "supplypilot.agentic.sql.postgres.username=agentic_pg_readonly_test",
        "supplypilot.agentic.sql.postgres.password=test-only"
})
class AgenticSqlAuthorizationIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext context;

    @Test
    void authenticatedNonProcurementRoleIsForbidden() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();

        mvc.perform(post("/api/v1/ai/agent-query")
                        .with(user("planner").roles("PRODUCTION_PLANNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"查詢採購資料\"}"))
                .andExpect(status().isForbidden());
    }
}

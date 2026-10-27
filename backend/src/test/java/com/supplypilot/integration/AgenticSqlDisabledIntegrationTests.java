package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import com.supplypilot.agentic.controller.AgenticSqlController;
import com.supplypilot.agentic.config.AgenticSqlDataSources;
import com.supplypilot.agentic.service.AgenticSqlAnswerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;

class AgenticSqlDisabledIntegrationTests extends AbstractIntegrationTest {
    @Autowired private WebApplicationContext context;

    @Test
    void featureOffCreatesNoAgenticEndpointServiceOrDatabasePools() {
        assertThat(context.getBeanNamesForType(AgenticSqlDataSources.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AgenticSqlController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AgenticSqlAnswerService.class)).isEmpty();
    }
}

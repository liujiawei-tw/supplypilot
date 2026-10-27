package com.supplypilot.agentic.controller;

import com.supplypilot.agentic.dto.AgenticSqlQueryRequest;
import com.supplypilot.agentic.dto.AgenticSqlQueryResponse;
import com.supplypilot.agentic.service.AgenticSqlAnswerService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai/agent-query")
@ConditionalOnProperty(prefix = "supplypilot.agentic.sql", name = "enabled", havingValue = "true")
public class AgenticSqlController {
    private final AgenticSqlAnswerService agent;

    public AgenticSqlController(AgenticSqlAnswerService agent) {
        this.agent = agent;
    }

    @PostMapping
    public AgenticSqlQueryResponse query(@Valid @RequestBody AgenticSqlQueryRequest request) {
        return agent.query(request.question());
    }
}

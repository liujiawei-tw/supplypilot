package com.supplypilot.ai.controller;

import com.supplypilot.ai.dto.AiQueryRequest;
import com.supplypilot.ai.dto.AiQueryResponse;
import com.supplypilot.ai.service.AiAssistantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiAssistantService assistant;

    public AiController(AiAssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/query")
    public AiQueryResponse query(@Valid @RequestBody AiQueryRequest request) {
        return assistant.query(request.message());
    }
}

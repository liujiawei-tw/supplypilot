package com.supplypilot.document.controller;

import com.supplypilot.document.dto.request.DocumentQueryRequest;
import com.supplypilot.document.dto.response.DocumentQueryResponse;
import com.supplypilot.document.rag.DocumentQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-queries")
public class DocumentQueryController {

    private final DocumentQueryService queries;

    public DocumentQueryController(DocumentQueryService queries) {
        this.queries = queries;
    }

    @PostMapping
    public DocumentQueryResponse query(@Valid @RequestBody DocumentQueryRequest request) {
        return queries.query(request.question());
    }
}

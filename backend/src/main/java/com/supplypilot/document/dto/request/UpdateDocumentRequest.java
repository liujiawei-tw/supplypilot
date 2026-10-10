package com.supplypilot.document.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.supplypilot.document.domain.DocumentStatus;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UpdateDocumentRequest {

    @Pattern(regexp = ".*\\S.*")
    @Size(max = 255)
    private String title;

    private DocumentStatus status;

    public UpdateDocumentRequest() {
    }

    public UpdateDocumentRequest(String title, DocumentStatus status) {
        this.title = title;
        this.status = status;
    }

    @JsonSetter("title")
    public void setTitle(String title) { this.title = title; }

    @JsonSetter("status")
    public void setStatus(DocumentStatus status) { this.status = status; }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }

    public String title() { return title; }
    public DocumentStatus status() { return status; }
}

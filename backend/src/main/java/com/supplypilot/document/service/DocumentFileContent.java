package com.supplypilot.document.service;

import org.springframework.core.io.InputStreamSource;
import org.springframework.http.MediaType;

/** A bounded document response body prepared from a document ID. */
public record DocumentFileContent(
        String filename,
        MediaType mediaType,
        long contentLength,
        InputStreamSource content
) {
}

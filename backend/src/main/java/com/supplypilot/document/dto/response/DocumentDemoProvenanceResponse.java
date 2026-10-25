package com.supplypilot.document.dto.response;

public record DocumentDemoProvenanceResponse(
        String datasetKey,
        String documentKey,
        String themeKey,
        String corpusVersion,
        String sourceSha256,
        String origin
) { }

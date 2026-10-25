package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DemoDatasetCoordinatorCatalogTests {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void catalogCanExpandBeyondTheFormerFixedSeedCountWhenCoverageIsComplete() {
        JsonNode catalog = catalog(List.of(
                seed("SUPPLIER_CONTRACT"), seed("SUPPLIER_CONTRACT"), seed("QUOTATION"),
                seed("PROCUREMENT_SOP"), seed("PRODUCT_SPECIFICATION"), seed("QUALITY_REQUIREMENT"),
                seed("SUPPLIER_PROFILE"), seed("SUPPLIER_COOPERATION_RECORD"), seed("SUPPLIER_EVALUATION")));

        assertThat(DemoDatasetCoordinator.requireValidSeedDocuments(catalog)).hasSize(9);
    }

    @Test
    void catalogSeedCountMustMatchAndEveryRequiredDocumentTypeMustBeCovered() {
        JsonNode missingType = catalog(List.of(seed("SUPPLIER_CONTRACT"), seed("QUOTATION"),
                seed("PROCUREMENT_SOP"), seed("PRODUCT_SPECIFICATION")));
        assertThatThrownBy(() -> DemoDatasetCoordinator.requireValidSeedDocuments(missingType))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing required seed document types");

        JsonNode countMismatch = objectMapper.valueToTree(Map.of(
                "seedDocumentCount", 16, "documents", validSeedRows()));
        assertThatThrownBy(() -> DemoDatasetCoordinator.requireValidSeedDocuments(countMismatch))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seedDocumentCount must match");
    }

    private JsonNode catalog(List<Map<String, Object>> documents) {
        return objectMapper.valueToTree(Map.of("seedDocumentCount", documents.size(), "documents", documents));
    }

    private static List<Map<String, Object>> validSeedRows() {
        List<Map<String, Object>> documents = new ArrayList<>();
        documents.add(seed("SUPPLIER_CONTRACT"));
        documents.add(seed("QUOTATION"));
        documents.add(seed("PROCUREMENT_SOP"));
        documents.add(seed("PRODUCT_SPECIFICATION"));
        documents.add(seed("QUALITY_REQUIREMENT"));
        return documents;
    }

    private static Map<String, Object> seed(String type) {
        return Map.of("classification", "SEED_BASELINE", "type", type, "documentKey", "DOC-" + type);
    }
}

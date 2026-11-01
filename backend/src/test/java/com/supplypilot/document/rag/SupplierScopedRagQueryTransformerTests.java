package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.document.semantic.SemanticProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;

class SupplierScopedRagQueryTransformerTests {
    private final SupplierScopedRagQueryTransformer transformer = new SupplierScopedRagQueryTransformer(
            new AiQueryPlanner(), new SemanticProperties("jdbc:postgresql://localhost/test", "test", "test",
                    "test-embedding", 768, "demo-generation-v2", true, 10, 0.4, Duration.ofSeconds(2)));

    @Test
    void explicitDemoSupplierCodeAddsScopeAndKeepsPublicationEligibilityFilters() {
        // This is the committed Demo corpus query for supplier-sup-003-supplier-profile-sp907-v2.txt.
        Query original = Query.builder().text("Sup-003 服務時段")
                .context(Map.of("retained-context", "retained-value")).build();

        Query transformed = transformer.transform(original);

        assertThat(transformed.context()).containsEntry("retained-context", "retained-value");
        assertThat(transformed.context().get(VectorStoreDocumentRetriever.FILTER_EXPRESSION))
                .isInstanceOf(Filter.Expression.class);
        String expression = transformed.context().get(VectorStoreDocumentRetriever.FILTER_EXPRESSION).toString();
        assertThat(expression)
                .contains("generationKey", "demo-generation-v2")
                .contains("documentStatus", "READY")
                .contains("outboundApprovalStatus", "APPROVED")
                .contains("supplierCode", "SUP-003");
    }

    @Test
    void multipleExplicitSupplierCodesStayWithinRequestedDemoSuppliers() {
        Query transformed = transformer.transform(new Query("SUP-003 與 SUP-007 的服務時段"));

        String expression = transformed.context().get(VectorStoreDocumentRetriever.FILTER_EXPRESSION).toString();
        assertThat(expression).contains("SUP-003", "SUP-007", "supplierCode");
    }

    @Test
    void supplierAliasUsesCanonicalDemoMetadataCode() {
        Query transformed = transformer.transform(new Query("SUPPLIER-003 服務時段"));

        String expression = transformed.context().get(VectorStoreDocumentRetriever.FILTER_EXPRESSION).toString();
        assertThat(expression).contains("supplierCode", "SUP-003").doesNotContain("SUPPLIER-003");
    }

    @Test
    void unscopedQuestionRetainsConfiguredPublishedGenerationFilter() {
        Query original = Query.builder().text("服務時段")
                .context(Map.of(VectorStoreDocumentRetriever.FILTER_EXPRESSION, "documentStatus == 'SKIPPED'"))
                .build();

        Query transformed = transformer.transform(original);

        assertThat(transformed).isNotSameAs(original);
        String dynamicFilter = transformed.context().get(VectorStoreDocumentRetriever.FILTER_EXPRESSION).toString();
        assertThat(dynamicFilter)
                .contains("generationKey", "demo-generation-v2")
                .contains("documentStatus", "READY")
                .contains("outboundApprovalStatus", "APPROVED")
                .doesNotContain("SKIPPED");
        assertThat(transformer.publishedApprovedGenerationFilter().toString())
                .contains("generationKey", "demo-generation-v2")
                .contains("documentStatus", "READY")
                .contains("outboundApprovalStatus", "APPROVED");
    }
}

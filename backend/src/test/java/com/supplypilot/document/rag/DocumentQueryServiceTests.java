package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.dto.response.DocumentQueryStatus;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import org.junit.jupiter.api.Test;

class DocumentQueryServiceTests {
    private static final String QUESTION = "SUP-001 的 MAT-001 標準交期是多久？";

    @Test
    void sendsOnlyThePolicySanitizedQuestionAndMapsVerifiedSourceFields() {
        String rawQuestion = " SUP-001 的 jane@example.com MAT-001 標準交期是多久？ ";
        String safeQuestion = "SUP-001 的 [EMAIL] MAT-001 標準交期是多久？";
        SpringAiRagAnswerPipeline pipeline = mock(SpringAiRagAnswerPipeline.class);
        VerifiedRagEvidence source = new VerifiedRagEvidence(
                27L, "SP-901-DOC-001", DocumentType.SUPPLIER_CONTRACT,
                "SUP-001 交付協議", "2.1", 8L, "SUP-001", "甲方供應商",
                3, 120, 184, "MAT-001 標準交期為 ORDERED 後 5 個曆日。", 0.87, false);
        when(pipeline.answer(safeQuestion)).thenReturn(
                new SpringAiRagAnswerPipeline.QueryResult(true, "MAT-001 標準交期為 5 個曆日。", List.of(source)));
        DocumentQueryService service = new DocumentQueryService(pipeline, new OutboundDocumentPolicy());

        var result = service.query(rawQuestion);

        assertThat(result.status()).isEqualTo(DocumentQueryStatus.ANSWERED);
        assertThat(result.answer()).isEqualTo("MAT-001 標準交期為 5 個曆日。");
        assertThat(result.sources()).singleElement().satisfies(mapped -> {
            assertThat(mapped.documentId()).isEqualTo(27L);
            assertThat(mapped.documentKey()).isEqualTo("SP-901-DOC-001");
            assertThat(mapped.documentType()).isEqualTo(DocumentType.SUPPLIER_CONTRACT);
            assertThat(mapped.title()).isEqualTo("SUP-001 交付協議");
            assertThat(mapped.version()).isEqualTo("2.1");
            assertThat(mapped.supplierId()).isEqualTo(8L);
            assertThat(mapped.supplierCode()).isEqualTo("SUP-001");
            assertThat(mapped.supplierName()).isEqualTo("甲方供應商");
            assertThat(mapped.chunkIndex()).isEqualTo(3);
            assertThat(mapped.startOffset()).isEqualTo(120);
            assertThat(mapped.endOffset()).isEqualTo(184);
            assertThat(mapped.excerpt()).isEqualTo(source.excerpt());
            assertThat(mapped.relevanceScore()).isEqualTo(0.87);
        });
        verify(pipeline).answer(safeQuestion);
    }

    @Test
    void rejectsSecretBearingQuestionBeforeCallingSpringAiPipeline() {
        SpringAiRagAnswerPipeline pipeline = mock(SpringAiRagAnswerPipeline.class);
        DocumentQueryService service = new DocumentQueryService(pipeline, new OutboundDocumentPolicy());

        assertThatThrownBy(() -> service.query("API key: secret-value，請查 SUP-001 合約"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(pipeline);
    }

    @Test
    void mapsEmptyVerifiedEvidenceToInsufficientEvidenceWithoutSources() {
        SpringAiRagAnswerPipeline pipeline = mock(SpringAiRagAnswerPipeline.class);
        when(pipeline.answer(QUESTION)).thenReturn(
                new SpringAiRagAnswerPipeline.QueryResult(false, null, List.of()));
        DocumentQueryService service = new DocumentQueryService(pipeline, new OutboundDocumentPolicy());

        var result = service.query(QUESTION);

        assertThat(result.status()).isEqualTo(DocumentQueryStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.answer()).isNull();
        assertThat(result.sources()).isEmpty();
        verify(pipeline).answer(QUESTION);
    }

    @Test
    void propagatesProviderUnavailableWithoutManufacturingAnAnswerOrSources() {
        SpringAiRagAnswerPipeline pipeline = mock(SpringAiRagAnswerPipeline.class);
        when(pipeline.answer(QUESTION)).thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        DocumentQueryService service = new DocumentQueryService(pipeline, new OutboundDocumentPolicy());

        assertThatThrownBy(() -> service.query(QUESTION))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));

        verify(pipeline).answer(QUESTION);
        verify(pipeline, never()).answer("API key: secret-value，請查 SUP-001 合約");
    }
}

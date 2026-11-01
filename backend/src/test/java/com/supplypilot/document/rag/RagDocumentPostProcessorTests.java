package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.supplypilot.demo.DemoCorpusRagEligibility;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.dto.response.DocumentSemanticIndexResponse;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.DocumentSemanticLifecycleReader;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.SemanticBackfillService;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.supplier.domain.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

class RagDocumentPostProcessorTests {
    private static final long DOCUMENT_ID = 27L;
    private static final long DOCUMENT_GENERATION = 4L;
    private static final String VERSION = "1.0";
    private static final String GENERATION_KEY = "gemini-embedding-001-768-spring-ai-token-v1";
    private static final String QUESTION = "SUP-001 的 MAT-001 標準交期是多久？";
    private static final String CONTENT = "SUP-001 supplies MAT-001\n"
            + "MAT-001 標準交期為 ORDERED 後 5 個曆日。";
    private static final double SCORE = 0.87;

    @Test
    void emitsOnlyApprovedSafeChunkAndBuildsBackendEvidenceFromLiveMetadata() {
        Fixture fixture = fixture(CONTENT, SCORE, 0, CONTENT.length());
        fixture.collector().begin();

        List<Document> context = fixture.processor().process(new Query(QUESTION), List.of(fixture.candidate()));

        assertThat(context).singleElement().satisfies(document -> {
            assertThat(document.getText()).contains("MAT-001 標準交期為 ORDERED 後 5 個曆日。");
            assertThat(document.getText()).doesNotContain("jane@example.com", "問答：", "預期依據：");
        });
        assertThat(fixture.collector().get()).singleElement().satisfies(evidence -> {
            assertThat(evidence.documentId()).isEqualTo(DOCUMENT_ID);
            assertThat(evidence.documentKey()).isEqualTo("SP-901-DOC-001");
            assertThat(evidence.documentType()).isEqualTo(DocumentType.SUPPLIER_CONTRACT);
            assertThat(evidence.title()).isEqualTo("SUP-001 交付協議");
            assertThat(evidence.version()).isEqualTo(VERSION);
            assertThat(evidence.supplierId()).isEqualTo(8L);
            assertThat(evidence.supplierCode()).isEqualTo("SUP-001");
            assertThat(evidence.supplierName()).isEqualTo("甲方供應商");
            assertThat(evidence.chunkIndex()).isZero();
            assertThat(evidence.startOffset()).isZero();
            assertThat(evidence.endOffset()).isEqualTo(CONTENT.length());
            assertThat(evidence.excerpt()).isEqualTo(context.getFirst().getText());
            assertThat(evidence.relevanceScore()).isEqualTo(SCORE);
        });
        verify(fixture.documents()).findForSemanticRead(DOCUMENT_ID);
        verify(fixture.lifecycle()).read(fixture.document());
    }

    @Test
    void leavesQuestionSupportJudgmentToModelAfterLiveSourceChecksPass() {
        Fixture fixture = fixture(CONTENT, SCORE, 0, CONTENT.length());
        String question = "SUP-001 的 MAT-001 付款方式是什麼？";
        when(fixture.demoEligibility().restrictBusinessFactContentWithSources(
                eq(fixture.document()), eq(question), anyString()))
                .thenAnswer(invocation -> DemoCorpusRagEligibility.RestrictedBusinessFactContent.unmodified(
                        invocation.getArgument(2)));
        when(fixture.demoEligibility().hasCompleteTemporalEvidence(anyString(), anyList(), anyBoolean()))
                .thenReturn(true);
        fixture.collector().begin();

        assertThat(new EvidenceValidator().supportedExcerpt(question, CONTENT)).isBlank();
        List<Document> context = fixture.processor().process(new Query(question), List.of(fixture.candidate()));

        assertThat(context).singleElement().satisfies(document ->
                assertThat(document.getText()).isEqualTo(CONTENT));
        assertThat(fixture.collector().get()).singleElement().satisfies(evidence ->
                assertThat(evidence.excerpt()).isEqualTo(CONTENT));
        verify(fixture.documents()).findForSemanticRead(DOCUMENT_ID);
        verify(fixture.lifecycle()).read(fixture.document());
    }

    @Test
    void usesOnlyRestrictedSameDocumentSafeChunksToScopeAnExactLocalExcerpt() {
        String identifierChunk = "SUP-001 supplies MAT-001.";
        String factChunk = "標準交期為 5 個曆日。";
        String sourceText = identifierChunk + "\n" + factChunk;
        DocumentChunk identifierSource = sourceChunk(0, 0, identifierChunk.length(), identifierChunk);
        DocumentChunk factSource = sourceChunk(1, identifierChunk.length() + 1,
                sourceText.length(), factChunk);
        List<DocumentChunk> source = List.of(identifierSource, factSource);
        String sourceHash = SemanticBackfillService.sourceHash(source);

        DocumentMetadata document = mock(DocumentMetadata.class);
        when(document.getId()).thenReturn(DOCUMENT_ID);
        when(document.getStatus()).thenReturn(DocumentStatus.READY);
        when(document.getVersion()).thenReturn(VERSION);
        when(document.getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.APPROVED);
        when(document.getSemanticReviewVersion()).thenReturn(VERSION);
        when(document.getSemanticIndexGeneration()).thenReturn(DOCUMENT_GENERATION);
        when(document.getSemanticApprovedSourceHash()).thenReturn(sourceHash);
        when(document.getDocumentType()).thenReturn(DocumentType.SUPPLIER_CONTRACT);
        when(document.getTitle()).thenReturn("SUP-001 交付協議");
        when(document.getDemoDocumentKey()).thenReturn("SP-901-DOC-001");
        when(document.isDemoBaseline()).thenReturn(false);
        Supplier supplier = mock(Supplier.class);
        when(supplier.getId()).thenReturn(8L);
        when(supplier.getCode()).thenReturn("SUP-001");
        when(supplier.getName()).thenReturn("甲方供應商");
        when(document.getSupplier()).thenReturn(supplier);

        DocumentMetadataRepository documents = mock(DocumentMetadataRepository.class);
        when(documents.findForSemanticRead(DOCUMENT_ID)).thenReturn(Optional.of(document));
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        when(chunks.findByDocumentIdOrderByChunkIndex(DOCUMENT_ID)).thenReturn(source);
        DocumentSemanticLifecycleReader lifecycle = mock(DocumentSemanticLifecycleReader.class);
        when(lifecycle.read(document)).thenReturn(new DocumentSemanticIndexResponse(
                "INDEXED", 41L, sourceHash, 2, 2, null, GENERATION_KEY, DOCUMENT_GENERATION));
        OutboundDocumentPolicy policy = mock(OutboundDocumentPolicy.class);
        DemoCorpusRagEligibility demoEligibility = mock(DemoCorpusRagEligibility.class);
        when(demoEligibility.isEligible(document)).thenReturn(true);
        List<String> safeChunks = List.of(identifierChunk, factChunk);
        when(demoEligibility.inspectSource(document, source, sourceHash, DOCUMENT_GENERATION, policy))
                .thenReturn(new OutboundDocumentPolicy.Decision("APPROVED", null, safeChunks));
        when(demoEligibility.restrictBusinessFactContentWithSources(document, QUESTION, identifierChunk))
                .thenReturn(DemoCorpusRagEligibility.RestrictedBusinessFactContent.unmodified(identifierChunk));
        when(demoEligibility.restrictBusinessFactContentWithSources(document, QUESTION, factChunk))
                .thenReturn(DemoCorpusRagEligibility.RestrictedBusinessFactContent.unmodified(factChunk));
        when(demoEligibility.hasCompleteTemporalEvidence(eq(QUESTION), anyList(), eq(false))).thenReturn(true);

        SemanticProperties properties = new SemanticProperties("jdbc:postgresql://localhost/test", "test", "test",
                "gemini-embedding-001", 768, GENERATION_KEY, true, 8, 0.45, Duration.ofSeconds(2));
        RagEvidenceCollector collector = new RagEvidenceCollector();
        RagDocumentPostProcessor processor = new RagDocumentPostProcessor(documents, chunks, lifecycle, policy,
                demoEligibility, properties, collector);
        Document candidate = Document.builder().id("vector-fact-chunk").text(factChunk).score(SCORE)
                .metadata(Map.of("documentId", DOCUMENT_ID, "documentVersion", VERSION,
                        "generationKey", GENERATION_KEY, "sourceHash", sourceHash,
                        "documentGeneration", DOCUMENT_GENERATION, "chunkIndex", 1,
                        "startOffset", identifierChunk.length() + 1, "endOffset", sourceText.length()))
                .build();
        collector.begin();

        List<Document> context = processor.process(new Query(QUESTION), List.of(candidate));

        assertThat(context).singleElement().satisfies(value -> assertThat(value.getText()).isEqualTo(factChunk));
        assertThat(collector.get()).singleElement().satisfies(evidence -> {
            assertThat(evidence.excerpt()).isEqualTo(factChunk);
            assertThat(evidence.startOffset()).isEqualTo(identifierChunk.length() + 1);
            assertThat(evidence.endOffset()).isEqualTo(sourceText.length());
        });
        verify(demoEligibility, org.mockito.Mockito.never())
                .restrictBusinessFactContentWithSources(document, QUESTION, identifierChunk);
        verify(demoEligibility).restrictBusinessFactContentWithSources(document, QUESTION, factChunk);
    }

    @Test
    void returnsExactFactScopedSourceOffsetsAndKeepsSyntheticScopeOnlyInContext() {
        String question = "SUP-001 的 MAT-001 標準交期是多久？";
        String sourceText = "SUP-001 supplies MAT-001. SUP-002 supplies MAT-002.\n"
                + "MAT-001 標準交期為 5 個日曆日。 MAT-002 標準交期為 12 個日曆日。";
        String factExcerpt = "MAT-001 標準交期為 5 個日曆日。";
        int excerptStart = sourceText.indexOf(factExcerpt);
        int absoluteStart = 700;
        List<DocumentChunk> source = List.of(sourceChunk(0, absoluteStart,
                absoluteStart + sourceText.length(), sourceText));
        Fixture fixture = fixture(source, List.of(sourceText), 0, SCORE, question);
        String context = "【現行條件】來源範圍 SUP-001 MAT-001。" + factExcerpt;
        when(fixture.demoEligibility().restrictBusinessFactContentWithSources(
                fixture.document(), question, sourceText))
                .thenReturn(new DemoCorpusRagEligibility.RestrictedBusinessFactContent(context, List.of(
                        new DemoCorpusRagEligibility.SourceExcerpt(factExcerpt, excerptStart,
                                excerptStart + factExcerpt.length()))));
        fixture.collector().begin();

        List<Document> contexts = fixture.processor().process(new Query(question), List.of(fixture.candidate()));

        assertThat(contexts).singleElement().satisfies(document -> {
            assertThat(document.getText()).contains("來源範圍 SUP-001 MAT-001", factExcerpt)
                    .doesNotContain("SUP-002", "MAT-002", "12 個日曆日");
        });
        assertThat(fixture.collector().get()).singleElement().satisfies(evidence -> {
            assertThat(evidence.excerpt()).isEqualTo(factExcerpt);
            assertThat(evidence.startOffset()).isEqualTo(absoluteStart + excerptStart);
            assertThat(evidence.endOffset()).isEqualTo(absoluteStart + excerptStart + factExcerpt.length());
            assertThat(evidence.endOffset() - evidence.startOffset()).isEqualTo(evidence.excerpt().length());
            assertThat(sourceText.substring(evidence.startOffset() - absoluteStart,
                    evidence.endOffset() - absoluteStart)).isEqualTo(evidence.excerpt());
            assertThat(evidence.excerpt()).doesNotContain("SUP-002", "MAT-002", "12 個日曆日", "來源範圍");
        });
    }

    @Test
    void lowScoreCandidateNeverLoadsOrEntersContext() {
        Fixture fixture = fixture(CONTENT, 0.44, 0, CONTENT.length());
        fixture.collector().begin();

        assertThatThrownBy(() -> fixture.processor().process(new Query(QUESTION), List.of(fixture.candidate())))
                .isInstanceOf(NoVerifiedRagEvidenceException.class);

        assertThat(fixture.collector().get()).isEmpty();
        verifyNoInteractions(fixture.documents(), fixture.chunks(), fixture.lifecycle(), fixture.policy(),
                fixture.demoEligibility());
    }

    @Test
    void nonReadyLiveDocumentCannotEnterContextEvenWhenVectorMetadataLooksCurrent() {
        Fixture fixture = fixture(CONTENT, SCORE, 0, CONTENT.length());
        when(fixture.document().getStatus()).thenReturn(DocumentStatus.INACTIVE);
        fixture.collector().begin();

        assertThatThrownBy(() -> fixture.processor().process(new Query(QUESTION), List.of(fixture.candidate())))
                .isInstanceOf(NoVerifiedRagEvidenceException.class);

        assertThat(fixture.collector().get()).isEmpty();
        verify(fixture.documents()).findForSemanticRead(DOCUMENT_ID);
        verify(fixture.chunks(), never()).findByDocumentIdOrderByChunkIndex(DOCUMENT_ID);
        verifyNoInteractions(fixture.lifecycle(), fixture.policy());
    }

    @Test
    void candidateWithOffsetsThatDoNotMatchTheMySqlSourceIsRejected() {
        Fixture fixture = fixture(CONTENT, SCORE, 1, CONTENT.length());
        fixture.collector().begin();

        assertThatThrownBy(() -> fixture.processor().process(new Query(QUESTION), List.of(fixture.candidate())))
                .isInstanceOf(NoVerifiedRagEvidenceException.class);

        assertThat(fixture.collector().get()).isEmpty();
    }

    @Test
    void preservesAnEarlierVerifiedHistoryMarkerWhenTheFactFallsInTheNextChunk() {
        String historyContext = "【歷史條件】SUP-001 MAT-001 歷史期間自 2025-01-01 起。";
        String historicalFact = "SUP-001 MAT-001 歷史標準交期為 5 個曆日。";
        List<DocumentChunk> source = List.of(
                sourceChunk(0, 0, historyContext.length(), historyContext),
                sourceChunk(1, historyContext.length(), historyContext.length() + historicalFact.length(),
                        historicalFact));
        Fixture fixture = fixture(source, List.of(historyContext, historicalFact), 1, SCORE,
                "SUP-001 的 MAT-001 歷史標準交期為幾天？");
        fixture.collector().begin();

        List<Document> context = fixture.processor().process(
                new Query("SUP-001 的 MAT-001 歷史標準交期為幾天？"), List.of(fixture.candidate()));

        assertThat(context).singleElement().satisfies(document ->
                assertThat(document.getText()).startsWith("【歷史條件】"));
        assertThat(fixture.collector().get()).singleElement().satisfies(evidence -> {
            assertThat(evidence.excerpt()).contains("歷史標準交期為 5 個曆日").doesNotContain("【歷史條件】");
            assertThat(evidence.startOffset()).isEqualTo(historyContext.length());
            assertThat(evidence.endOffset()).isEqualTo(historyContext.length() + historicalFact.length());
            assertThat(evidence.verifiedTemporalRole()).isEqualTo("HISTORICAL");
            assertThat(evidence.temporalRoleStartOffset()).isZero();
            assertThat(evidence.temporalRoleEndOffset()).isEqualTo("【歷史條件】".length());
        });
    }

    @Test
    void doesNotCarryAHistoryMarkerAcrossAnUncoveredSourceOffsetGap() {
        String historyContext = "【歷史條件】SUP-001 MAT-001 歷史期間自 2025-01-01 起。";
        String historicalFact = "SUP-001 MAT-001 歷史標準交期為 5 個曆日。";
        int factStart = historyContext.length() + 4;
        List<DocumentChunk> source = List.of(
                sourceChunk(0, 0, historyContext.length(), historyContext),
                sourceChunk(1, factStart, factStart + historicalFact.length(), historicalFact));
        Fixture fixture = fixture(source, List.of(historyContext, historicalFact), 1, SCORE,
                "SUP-001 的 MAT-001 歷史標準交期為幾天？");
        fixture.collector().begin();

        fixture.processor().process(new Query("SUP-001 的 MAT-001 歷史標準交期為幾天？"),
                List.of(fixture.candidate()));

        assertThat(fixture.collector().get()).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.excerpt()).doesNotContain("【歷史條件】");
                    assertThat(evidence.verifiedTemporalRole()).isNull();
                });
    }

    @Test
    void rawCandidateCannotBypassTheApprovedMaskedChunk() {
        String raw = "SUP-001 contact jane@example.com supplies MAT-001\n"
                + "MAT-001 標準交期為 ORDERED 後 5 個曆日。";
        Fixture fixture = fixture(raw, SCORE, 0, raw.length());
        String safe = "SUP-001 contact [EMAIL] supplies MAT-001\n"
                + "MAT-001 標準交期為 ORDERED 後 5 個曆日。";
        fixture.setApprovedSafeChunk(safe);
        fixture.collector().begin();

        assertThatThrownBy(() -> fixture.processor().process(new Query(QUESTION), List.of(fixture.candidate())))
                .isInstanceOf(NoVerifiedRagEvidenceException.class);

        assertThat(fixture.collector().get()).isEmpty();
        assertThat(fixture.candidate().getText()).contains("jane@example.com");
    }

    private Fixture fixture(String sourceText, double score, int candidateStart, int candidateEnd) {
        DocumentChunk sourceChunk = sourceChunk(0, 0, sourceText.length(), sourceText);
        return fixture(List.of(sourceChunk), List.of(sourceText), 0, score, candidateStart, candidateEnd);
    }

    private Fixture fixture(List<DocumentChunk> source, List<String> safeChunks, int candidatePosition,
            double score) {
        return fixture(source, safeChunks, candidatePosition, score, QUESTION);
    }

    private Fixture fixture(List<DocumentChunk> source, List<String> safeChunks, int candidatePosition,
            double score, String question) {
        DocumentChunk candidateSource = source.get(candidatePosition);
        return fixture(source, safeChunks, candidatePosition, score,
                candidateSource.getStartOffset(), candidateSource.getEndOffset(), question);
    }

    private Fixture fixture(List<DocumentChunk> source, List<String> safeChunks, int candidatePosition,
            double score, int candidateStart, int candidateEnd) {
        return fixture(source, safeChunks, candidatePosition, score, candidateStart, candidateEnd, QUESTION);
    }

    private Fixture fixture(List<DocumentChunk> source, List<String> safeChunks, int candidatePosition,
            double score, int candidateStart, int candidateEnd, String question) {
        DocumentMetadataRepository documents = mock(DocumentMetadataRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        DocumentSemanticLifecycleReader lifecycle = mock(DocumentSemanticLifecycleReader.class);
        OutboundDocumentPolicy policy = mock(OutboundDocumentPolicy.class);
        DemoCorpusRagEligibility demoEligibility = mock(DemoCorpusRagEligibility.class);
        RagEvidenceCollector collector = new RagEvidenceCollector();

        DocumentMetadata document = mock(DocumentMetadata.class);
        when(document.getStatus()).thenReturn(DocumentStatus.READY);
        when(document.getVersion()).thenReturn(VERSION);
        when(document.getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.APPROVED);
        when(document.getSemanticReviewVersion()).thenReturn(VERSION);
        when(document.getSemanticIndexGeneration()).thenReturn(DOCUMENT_GENERATION);
        when(document.getDocumentType()).thenReturn(DocumentType.SUPPLIER_CONTRACT);
        when(document.getTitle()).thenReturn("SUP-001 交付協議");
        when(document.getDemoDocumentKey()).thenReturn("SP-901-DOC-001");
        when(document.isDemoBaseline()).thenReturn(false);

        Supplier supplier = mock(Supplier.class);
        when(supplier.getId()).thenReturn(8L);
        when(supplier.getCode()).thenReturn("SUP-001");
        when(supplier.getName()).thenReturn("甲方供應商");
        when(document.getSupplier()).thenReturn(supplier);

        String sourceHash = SemanticBackfillService.sourceHash(source);
        when(document.getSemanticApprovedSourceHash()).thenReturn(sourceHash);
        when(documents.findForSemanticRead(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(chunks.findByDocumentIdOrderByChunkIndex(DOCUMENT_ID)).thenReturn(source);
        when(demoEligibility.isEligible(document)).thenReturn(true);
        when(demoEligibility.inspectSource(document, source, sourceHash, DOCUMENT_GENERATION, policy))
                .thenReturn(new OutboundDocumentPolicy.Decision("APPROVED", null, safeChunks));
        when(demoEligibility.restrictBusinessFactContentWithSources(eq(document), eq(question),
                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> DemoCorpusRagEligibility.RestrictedBusinessFactContent.unmodified(
                        invocation.getArgument(2)));
        DocumentChunk sourceChunk = source.getFirst();
        String sourceText = sourceChunk.getContent();
        when(demoEligibility.hasCompleteTemporalEvidence(eq(question), anyList(), eq(false))).thenReturn(true);
        when(lifecycle.read(document)).thenReturn(new DocumentSemanticIndexResponse(
                "INDEXED", 41L, sourceHash, source.size(), source.size(), null, GENERATION_KEY,
                DOCUMENT_GENERATION));

        SemanticProperties properties = new SemanticProperties("jdbc:postgresql://localhost/test", "test", "test",
                "gemini-embedding-001", 768, GENERATION_KEY, true, 5, 0.45, Duration.ofSeconds(2));
        RagDocumentPostProcessor processor = new RagDocumentPostProcessor(documents, chunks, lifecycle, policy,
                demoEligibility, properties, collector);
        Document candidate = candidate(safeChunks.get(candidatePosition), score, candidateStart, candidateEnd,
                sourceHash, source.get(candidatePosition).getChunkIndex());
        return new Fixture(processor, collector, documents, chunks, lifecycle, policy, demoEligibility,
                document, candidate, sourceChunk, sourceHash);
    }

    private Document candidate(String text, double score, int start, int end, String sourceHash) {
        return candidate(text, score, start, end, sourceHash, 0);
    }

    private Document candidate(String text, double score, int start, int end, String sourceHash, int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", DOCUMENT_ID);
        metadata.put("documentVersion", VERSION);
        metadata.put("generationKey", GENERATION_KEY);
        metadata.put("sourceHash", sourceHash);
        metadata.put("documentGeneration", DOCUMENT_GENERATION);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("startOffset", start);
        metadata.put("endOffset", end);
        metadata.put("documentStatus", DocumentStatus.READY.name());
        metadata.put("outboundApprovalStatus", SemanticReviewStatus.APPROVED.name());
        metadata.put("documentType", DocumentType.SUPPLIER_CONTRACT.name());
        metadata.put("title", "SUP-001 交付協議");
        return Document.builder().id("vector-chunk-0").text(text).metadata(metadata).score(score).build();
    }

    private DocumentChunk sourceChunk(int index, int start, int end, String content) {
        DocumentChunk source = mock(DocumentChunk.class);
        when(source.getChunkIndex()).thenReturn(index);
        when(source.getStartOffset()).thenReturn(start);
        when(source.getEndOffset()).thenReturn(end);
        when(source.getContent()).thenReturn(content);
        return source;
    }

    private record Fixture(RagDocumentPostProcessor processor, RagEvidenceCollector collector,
            DocumentMetadataRepository documents, DocumentChunkRepository chunks,
            DocumentSemanticLifecycleReader lifecycle, OutboundDocumentPolicy policy,
            DemoCorpusRagEligibility demoEligibility, DocumentMetadata document,
            Document candidate, DocumentChunk sourceChunk, String sourceHash) {
        private void setApprovedSafeChunk(String safeChunk) {
            org.mockito.Mockito.when(demoEligibility.inspectSource(document, List.of(sourceChunk), sourceHash,
                    DOCUMENT_GENERATION, policy))
                    .thenReturn(new OutboundDocumentPolicy.Decision("APPROVED", null, List.of(safeChunk)));
            org.mockito.Mockito.when(demoEligibility.restrictBusinessFactContentWithSources(document, QUESTION,
                    safeChunk)).thenReturn(DemoCorpusRagEligibility.RestrictedBusinessFactContent.unmodified(
                            safeChunk));
        }
    }
}

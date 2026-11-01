package com.supplypilot.document.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.supplypilot.demo.DemoCorpusRagEligibility;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.DocumentSemanticLifecycleReader;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.SemanticBackfillService;
import com.supplypilot.document.semantic.SemanticProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.stereotype.Component;

/** Rechecks live MySQL status and outbound policy before a retrieved document can enter Gemini context. */
@Component
public class RagDocumentPostProcessor implements DocumentPostProcessor {
    private static final Pattern TEMPORAL_ROLE_MARKER = Pattern.compile("【(?:歷史|現行)條件】");

    private final DocumentMetadataRepository documents;
    private final DocumentChunkRepository chunks;
    private final DocumentSemanticLifecycleReader lifecycle;
    private final OutboundDocumentPolicy policy;
    private final DemoCorpusRagEligibility demoEligibility;
    private final SemanticProperties properties;
    private final RagEvidenceCollector collector;

    public RagDocumentPostProcessor(DocumentMetadataRepository documents, DocumentChunkRepository chunks,
            DocumentSemanticLifecycleReader lifecycle, OutboundDocumentPolicy policy,
            DemoCorpusRagEligibility demoEligibility, SemanticProperties properties,
            RagEvidenceCollector collector) {
        this.documents = documents;
        this.chunks = chunks;
        this.lifecycle = lifecycle;
        this.policy = policy;
        this.demoEligibility = demoEligibility;
        this.properties = properties;
        this.collector = collector;
    }

    @Override
    public List<Document> process(Query query, List<Document> retrieved) {
        Map<Long, SourceSnapshot> snapshots = new HashMap<>();
        List<Validated> validated = new ArrayList<>();
        for (Document candidate : retrieved) {
            Validated result = validate(query.text(), candidate, snapshots);
            if (result != null) validated.add(result);
        }
        validated.sort(Comparator.comparingDouble((Validated item) -> item.evidence().relevanceScore())
                .reversed().thenComparing(item -> item.evidence().documentId())
                .thenComparingInt(item -> item.evidence().chunkIndex()));
        List<Validated> limited = validated.stream().limit(properties.topK()).toList();
        boolean hasDemo = limited.stream().anyMatch(item -> item.evidence().demoBaseline());
        if (limited.isEmpty() || !demoEligibility.hasCompleteTemporalEvidence(query.text(),
                limited.stream().map(item -> item.evidence().verifiedTemporalExcerpt()).toList(), hasDemo)) {
            collector.set(List.of());
            throw new NoVerifiedRagEvidenceException();
        }
        collector.set(limited.stream().map(Validated::evidence).toList());
        return limited.stream().map(Validated::contextDocument).toList();
    }

    private Validated validate(String question, Document candidate, Map<Long, SourceSnapshot> snapshots) {
        Map<String, Object> metadata = candidate.getMetadata();
        Long documentId = longValue(metadata.get("documentId"));
        String version = stringValue(metadata.get("documentVersion"));
        String generationKey = stringValue(metadata.get("generationKey"));
        String sourceHash = stringValue(metadata.get("sourceHash"));
        Long documentGeneration = longValue(metadata.get("documentGeneration"));
        Integer chunkIndex = intValue(metadata.get("chunkIndex"));
        Integer startOffset = intValue(metadata.get("startOffset"));
        Integer endOffset = intValue(metadata.get("endOffset"));
        Double score = candidate.getScore();
        if (documentId == null || version == null || generationKey == null || sourceHash == null
                || documentGeneration == null || chunkIndex == null || startOffset == null || endOffset == null
                || !properties.generationKey().equals(generationKey) || score == null || !Double.isFinite(score)
                || score < properties.similarityThreshold()) return null;

        SourceSnapshot snapshot = snapshots.computeIfAbsent(documentId, this::loadSnapshot);
        if (snapshot == null) return null;
        DocumentMetadata document = snapshot.document();
        if (!version.equals(document.getVersion()) || document.getStatus() != DocumentStatus.READY
                || document.getSemanticReviewStatus() != SemanticReviewStatus.APPROVED
                || document.getSemanticIndexGeneration() != documentGeneration
                || !sourceHash.equals(snapshot.sourceHash())
                || !sourceHash.equals(document.getSemanticApprovedSourceHash())
                || !version.equals(document.getSemanticReviewVersion())) return null;

        DocumentChunk source = snapshot.chunks().stream()
                .filter(chunk -> chunk.getChunkIndex() == chunkIndex
                        && chunk.getStartOffset() == startOffset && chunk.getEndOffset() == endOffset)
                .findFirst().orElse(null);
        if (source == null) return null;
        OutboundDocumentPolicy.Decision decision = snapshot.decision();
        int sourcePosition = snapshot.chunks().indexOf(source);
        if (sourcePosition < 0 || sourcePosition >= decision.safeChunks().size()
                || !candidate.getText().equals(decision.safeChunks().get(sourcePosition))) {
            return null;
        }

        String applicable = demoEligibility.restrictBusinessFactContent(document, question, candidate.getText());
        if (applicable == null || applicable.isBlank()) return null;
        String excerpt = applicable.strip();
        if (excerpt.isBlank()) return null;
        TemporalRoleContext temporalRole = TEMPORAL_ROLE_MARKER.matcher(excerpt).find()
                ? null : verifiedTemporalRoleAtOffset(snapshot, source, sourcePosition);
        List<String> verifiedMaterialCodes = IdentifierMatcher.find(question).stream()
                .flatMap(identifier -> java.util.Arrays.stream(identifier.split("/")))
                .filter(identifier -> identifier.startsWith("MAT-") || identifier.startsWith("MATERIAL-"))
                .toList();

        var supplier = document.getSupplier();
        VerifiedRagEvidence evidence = new VerifiedRagEvidence(documentId, document.getDemoDocumentKey(),
                document.getDocumentType(), document.getTitle(), version,
                supplier == null ? null : supplier.getId(), supplier == null ? null : supplier.getCode(),
                supplier == null ? null : supplier.getName(), chunkIndex, startOffset, endOffset,
                excerpt, score, document.isDemoBaseline(),
                temporalRole == null ? null : temporalRole.role(),
                temporalRole == null ? null : temporalRole.startOffset(),
                temporalRole == null ? null : temporalRole.endOffset(),
                verifiedMaterialCodes.size() == 1 ? java.util.Set.copyOf(verifiedMaterialCodes)
                        : java.util.Set.of());
        String contextExcerpt = temporalRole == null ? excerpt : temporalRole.marker() + " " + excerpt;
        Document context = Document.builder().id(candidate.getId()).text(contextExcerpt)
                .metadata(candidate.getMetadata()).score(score).build();
        return new Validated(evidence, context);
    }

    private TemporalRoleContext verifiedTemporalRoleAtOffset(SourceSnapshot snapshot,
            DocumentChunk target, int targetPosition) {
        String activeRole = "";
        int activeStartOffset = -1;
        int activeEndOffset = -1;
        int coveredThrough = snapshot.chunks().getFirst().getStartOffset();
        for (int index = 0; index <= targetPosition; index++) {
            DocumentChunk chunk = snapshot.chunks().get(index);
            int startOffset = chunk.getStartOffset();
            if (startOffset > coveredThrough) {
                // An uncovered source range could contain a new section marker. Do not carry a
                // role across it, even though these chunks belong to the same approved document.
                activeRole = "";
                activeStartOffset = -1;
                activeEndOffset = -1;
            }
            Matcher marker = TEMPORAL_ROLE_MARKER.matcher(chunk.getContent());
            while (marker.find()) {
                int markerStart = startOffset + marker.start();
                int markerEnd = startOffset + marker.end();
                if (markerEnd <= target.getStartOffset()) {
                    activeRole = marker.group().contains("歷史") ? "HISTORICAL" : "CURRENT";
                    activeStartOffset = markerStart;
                    activeEndOffset = markerEnd;
                }
            }
            coveredThrough = Math.max(coveredThrough, chunk.getEndOffset());
        }
        if (activeRole.isBlank() || activeStartOffset < 0 || activeEndOffset <= activeStartOffset) return null;
        return new TemporalRoleContext(activeRole,
                activeRole.equals("HISTORICAL") ? "【歷史條件】" : "【現行條件】",
                activeStartOffset, activeEndOffset);
    }

    private SourceSnapshot loadSnapshot(Long documentId) {
        DocumentMetadata document = documents.findForSemanticRead(documentId).orElse(null);
        if (document == null || document.getStatus() != DocumentStatus.READY
                || !demoEligibility.isEligible(document)) return null;
        List<DocumentChunk> source = chunks.findByDocumentIdOrderByChunkIndex(documentId);
        if (source.isEmpty()) return null;
        String sourceHash = SemanticBackfillService.sourceHash(source);
        if (document.getSemanticReviewStatus() != SemanticReviewStatus.APPROVED
                || !sourceHash.equals(document.getSemanticApprovedSourceHash())
                || !document.getVersion().equals(document.getSemanticReviewVersion())) return null;
        OutboundDocumentPolicy.Decision decision = demoEligibility.inspectSource(document, source,
                sourceHash, document.getSemanticIndexGeneration(), policy);
        if (!"APPROVED".equals(decision.status()) || decision.safeChunks().size() != source.size()) return null;
        if (!"INDEXED".equals(lifecycle.read(document).status())) return null;
        return new SourceSnapshot(document, source, sourceHash, decision);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try { return Long.valueOf(text); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try { return Integer.valueOf(text); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    private record SourceSnapshot(DocumentMetadata document, List<DocumentChunk> chunks,
                                  String sourceHash, OutboundDocumentPolicy.Decision decision) { }
    private record Validated(VerifiedRagEvidence evidence, Document contextDocument) { }
    private record TemporalRoleContext(String role, String marker, int startOffset, int endOffset) { }
}

package com.supplypilot.document.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.semantic.SemanticRetrievalService;
import org.springframework.stereotype.Service;

/** Fuses safe MySQL lexical candidates with verified pgvector candidates using reciprocal rank fusion. */
@Service
public class HybridRetrievalService {
    private static final int RRF_CONSTANT = 60;

    private final DocumentRetrievalService lexical;
    private final SemanticRetrievalService semantic;
    private final SemanticProperties properties;
    private final OutboundDocumentPolicy policy;

    public HybridRetrievalService(DocumentRetrievalService lexical, SemanticRetrievalService semantic,
                                  SemanticProperties properties, OutboundDocumentPolicy policy) {
        this.lexical = lexical;
        this.semantic = semantic;
        this.properties = properties;
        this.policy = policy;
    }

    public List<RetrievedChunk> retrieve(String question) {
        String safeQuestion = policy.safeQuery(question);
        List<RetrievedChunk> lexicalCandidates = lexical.retrieve(safeQuestion);
        List<RetrievedChunk> semanticCandidates = properties.readEnabled()
                ? semantic.retrieve(safeQuestion) : List.of();
        return fuse(lexicalCandidates, semanticCandidates, properties.topK());
    }

    static List<RetrievedChunk> fuse(List<RetrievedChunk> lexicalCandidates,
                                    List<RetrievedChunk> semanticCandidates, int limit) {
        Map<Key, RankedCandidate> candidates = new LinkedHashMap<>();
        Set<Key> conflicted = new HashSet<>();
        addRanks(candidates, conflicted, lexicalCandidates, false);
        addRanks(candidates, conflicted, semanticCandidates, true);
        double maximum = 2.0 / (RRF_CONSTANT + 1);
        return candidates.entrySet().stream()
                .filter(entry -> !conflicted.contains(entry.getKey()))
                .map(entry -> new RetrievedChunk(entry.getValue().chunk,
                        Math.min(1.0, entry.getValue().rankScore / maximum)))
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                        .thenComparing(result -> key(result.chunk())))
                .limit(limit)
                .toList();
    }

    private static void addRanks(Map<Key, RankedCandidate> candidates, Set<Key> conflicted,
                                 List<RetrievedChunk> results, boolean semantic) {
        for (int index = 0; index < results.size(); index++) {
            DocumentChunk chunk = results.get(index).chunk();
            Key key = key(chunk);
            if (key.documentId == null || key.version == null || conflicted.contains(key)) continue;
            RankedCandidate candidate = candidates.get(key);
            if (candidate == null) {
                candidate = new RankedCandidate(chunk);
                candidates.put(key, candidate);
            } else if (!sameSource(candidate.chunk, chunk)) {
                candidates.remove(key);
                conflicted.add(key);
                continue;
            }
            double reciprocalRank = 1.0 / (RRF_CONSTANT + index + 1);
            candidate.rankScore += reciprocalRank;
        }
    }

    private static boolean sameSource(DocumentChunk left, DocumentChunk right) {
        return left.getStartOffset() == right.getStartOffset()
                && left.getEndOffset() == right.getEndOffset()
                && left.getContent().equals(right.getContent());
    }

    private static Key key(DocumentChunk chunk) {
        DocumentMetadata document = chunk.getDocument();
        return new Key(document == null ? null : document.getId(),
                document == null ? null : document.getVersion(), chunk.getChunkIndex());
    }

    private record Key(Long documentId, String version, int chunkIndex)
            implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int compare = Comparator.nullsLast(Long::compareTo).compare(documentId, other.documentId);
            if (compare != 0) return compare;
            compare = Comparator.nullsLast(String::compareTo).compare(version, other.version);
            if (compare != 0) return compare;
            return Integer.compare(chunkIndex, other.chunkIndex);
        }
    }

    private static final class RankedCandidate {
        private final DocumentChunk chunk;
        private double rankScore;

        private RankedCandidate(DocumentChunk chunk) {
            this.chunk = chunk;
        }
    }
}

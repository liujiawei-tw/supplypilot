package com.supplypilot.document.rag;

import java.util.ArrayList;
import java.util.List;
import java.time.Clock;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.dto.response.DocumentResponse;
import com.supplypilot.document.mapper.DocumentMapper;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.DocumentSemanticLifecycleReader;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class DocumentIngestionService {

    private final DocumentMetadataRepository documents;
    private final DocumentTextExtractor extractor;
    private final TextChunker chunker;
    private final EmbeddingProvider embeddings;
    private final DocumentIndexPersistenceService persistence;
    private final DocumentMapper mapper;
    private final DocumentSemanticLifecycleReader lifecycleReader;
    private final Clock clock;

    public DocumentIngestionService(
            DocumentMetadataRepository documents,
            DocumentTextExtractor extractor,
            TextChunker chunker,
            EmbeddingProvider embeddings,
            DocumentIndexPersistenceService persistence,
            DocumentMapper mapper,
            DocumentSemanticLifecycleReader lifecycleReader,
            Clock clock
    ) {
        this.documents = documents;
        this.extractor = extractor;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.persistence = persistence;
        this.mapper = mapper;
        this.lifecycleReader = lifecycleReader;
        this.clock = clock;
    }

    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'ADMIN')")
    public DocumentResponse ingest(Long documentId) {
        return ingestInternal(documentId);
    }

    /** Internal demo bootstrap seam; only persisted trusted corpus rows can use it without a user role. */
    public DocumentResponse ingestTrustedDemoBaseline(Long documentId) {
        DocumentMetadata document = documents.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (!document.isDemoBaseline()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if (document.getStatus() == DocumentStatus.FAILED) {
            document.retryFailedDemoBaselineIngestion(clock.instant());
            documents.saveAndFlush(document);
        }
        return ingestInternal(documentId);
    }

    private DocumentResponse ingestInternal(Long documentId) {
        DocumentMetadata document = documents.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (document.getStatus() != DocumentStatus.PROCESSING && document.getStatus() != DocumentStatus.READY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        final String text;
        try {
            text = extractor.extract(document.getStoragePath());
        } catch (DocumentParsingException exception) {
            return toResponse(persistence.markFailedIfProcessing(documentId).id());
        }

        final List<TextChunker.TextChunk> textChunks;
        try {
            textChunks = chunker.chunk(text);
        } catch (RuntimeException exception) {
            return toResponse(persistence.markFailedIfProcessing(documentId).id());
        }

        List<PreparedChunk> prepared = new ArrayList<>();
        try {
            for (TextChunker.TextChunk chunk : textChunks) {
                String embedding = EmbeddingCodec.encode(embeddings.embed(chunk.content()));
                prepared.add(new PreparedChunk(chunk.index(), chunk.startOffset(), chunk.endOffset(),
                        chunk.content(), embedding));
            }
            if (prepared.isEmpty()) {
                return toResponse(persistence.markFailedIfProcessing(documentId).id());
            }
        } catch (BusinessException exception) {
            persistence.markFailedIfProcessing(documentId);
            throw exception;
        } catch (IllegalArgumentException exception) {
            persistence.markFailedIfProcessing(documentId);
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        } catch (RuntimeException exception) {
            persistence.markFailedIfProcessing(documentId);
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }

        try {
            persistence.replaceIndex(documentId, List.copyOf(prepared));
            return toResponse(documentId);
        } catch (RuntimeException persistenceFailure) {
            try {
                persistence.markFailedIfProcessing(documentId);
            } catch (RuntimeException compensationFailure) {
                persistenceFailure.addSuppressed(compensationFailure);
            }
            throw persistenceFailure;
        }
    }

    private DocumentResponse toResponse(Long documentId) {
        DocumentMetadata document = documents.findForSemanticRead(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        return mapper.toResponse(document, lifecycleReader.read(document));
    }
}

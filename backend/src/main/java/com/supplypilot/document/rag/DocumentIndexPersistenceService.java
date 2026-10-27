package com.supplypilot.document.rag;

import java.time.Clock;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.dto.response.DocumentResponse;
import com.supplypilot.document.mapper.DocumentMapper;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class DocumentIndexPersistenceService {

    private final DocumentMetadataRepository documents;
    private final DocumentChunkRepository chunks;
    private final Clock clock;
    private final DocumentMapper mapper;
    private final EntityManager entityManager;

    DocumentIndexPersistenceService(
            DocumentMetadataRepository documents,
            DocumentChunkRepository chunks,
            Clock clock,
            DocumentMapper mapper,
            EntityManager entityManager
    ) {
        this.documents = documents;
        this.chunks = chunks;
        this.clock = clock;
        this.mapper = mapper;
        this.entityManager = entityManager;
    }

    @Transactional
    public DocumentResponse replaceIndex(Long documentId, java.util.List<PreparedChunk> prepared) {
        DocumentMetadata document = findForUpdate(documentId);
        if (document.getStatus() != DocumentStatus.PROCESSING && document.getStatus() != DocumentStatus.READY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        chunks.deleteByDocumentId(documentId);
        chunks.flush();
        chunks.saveAll(prepared.stream().map(chunk -> DocumentChunk.create(
                document, document.getVersion(), chunk.index(), chunk.startOffset(), chunk.endOffset(), chunk.content(),
                chunk.embedding(), clock.instant())).toList());
        if (document.getStatus() == DocumentStatus.PROCESSING) {
            document.markReady(clock.instant());
        }
        return mapper.toResponse(documents.save(document));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DocumentResponse markFailedIfProcessing(Long documentId) {
        DocumentMetadata document = findForUpdate(documentId);
        if (document.getStatus() == DocumentStatus.PROCESSING) {
            chunks.deleteByDocumentId(documentId);
            document.markFailed(clock.instant());
            return mapper.toResponse(documents.save(document));
        }
        return mapper.toResponse(document);
    }

    private DocumentMetadata findForUpdate(Long id) {
        DocumentMetadata document = documents.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        // A preceding request-level read may have placed an older status in this persistence context.
        entityManager.refresh(document);
        return document;
    }
}

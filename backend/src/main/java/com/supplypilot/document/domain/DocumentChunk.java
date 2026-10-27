package com.supplypilot.document.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "document_chunks", uniqueConstraints =
        @UniqueConstraint(name = "uq_document_chunks_document_index", columnNames = {"document_id", "chunk_index"}))
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentMetadata document;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "document_version", length = 50)
    private String documentVersion;

    @Column(name = "start_offset", nullable = false)
    private int startOffset;

    @Column(name = "end_offset", nullable = false)
    private int endOffset;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String embedding;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DocumentChunk() {
    }

    /** Keeps existing in-process retrieval projections tied to the owning document's version. */
    public static DocumentChunk create(
            DocumentMetadata document,
            int chunkIndex,
            int startOffset,
            int endOffset,
            String content,
            String embedding,
            Instant createdAt
    ) {
        return create(document, document.getVersion(), chunkIndex, startOffset, endOffset,
                content, embedding, createdAt);
    }

    public static DocumentChunk create(
            DocumentMetadata document,
            String documentVersion,
            int chunkIndex,
            int startOffset,
            int endOffset,
            String content,
            String embedding,
            Instant createdAt
    ) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.document = Objects.requireNonNull(document);
        chunk.documentVersion = Objects.requireNonNull(documentVersion);
        chunk.chunkIndex = chunkIndex;
        chunk.startOffset = startOffset;
        chunk.endOffset = endOffset;
        chunk.content = Objects.requireNonNull(content);
        chunk.embedding = Objects.requireNonNull(embedding);
        chunk.createdAt = Objects.requireNonNull(createdAt);
        return chunk;
    }

    public Long getId() { return id; }
    public DocumentMetadata getDocument() { return document; }
    public int getChunkIndex() { return chunkIndex; }
    public String getDocumentVersion() { return documentVersion; }
    public int getStartOffset() { return startOffset; }
    public int getEndOffset() { return endOffset; }
    public String getContent() { return content; }
    public String getEmbedding() { return embedding; }
    public Instant getCreatedAt() { return createdAt; }
}

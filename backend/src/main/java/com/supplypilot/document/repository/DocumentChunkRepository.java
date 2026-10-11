package com.supplypilot.document.repository;

import java.util.List;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    void deleteByDocumentId(Long documentId);

    long countByDocumentId(Long documentId);

    @Query("""
            select chunk from DocumentChunk chunk
            join fetch chunk.document document
            left join fetch document.supplier
            where document.status = :status
            order by chunk.id
            """)
    List<DocumentChunk> findRetrievable(@Param("status") DocumentStatus status);
}

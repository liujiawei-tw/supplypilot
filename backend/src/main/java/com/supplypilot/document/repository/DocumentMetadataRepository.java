package com.supplypilot.document.repository;

import java.util.Optional;
import java.util.List;

import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, Long>,
        JpaSpecificationExecutor<DocumentMetadata> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select document from DocumentMetadata document where document.id = :id")
    Optional<DocumentMetadata> findByIdForUpdate(@Param("id") Long id);

    @Query("select d from DocumentMetadata d left join fetch d.supplier where d.id = :id")
    Optional<DocumentMetadata> findForSemanticRead(@Param("id") Long id);

    @Query("select d from DocumentMetadata d where d.status = :status and d.id > :afterId order by d.id")
    List<DocumentMetadata> findBackfillPage(@Param("status") DocumentStatus status,
                                            @Param("afterId") Long afterId,
                                            org.springframework.data.domain.Pageable pageable);

    boolean existsByDocumentTypeAndTitleAndVersion(DocumentType documentType, String title, String version);
}

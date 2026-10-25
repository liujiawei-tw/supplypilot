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

    @Query("select d from DocumentMetadata d left join fetch d.supplier "
            + "where d.demoDatasetKey = :datasetKey and d.demoDocumentKey = :documentKey "
            + "order by d.id")
    List<DocumentMetadata> findDemoBaselineCandidates(@Param("datasetKey") String datasetKey,
                                                       @Param("documentKey") String documentKey);

    @Query("select d from DocumentMetadata d left join fetch d.supplier "
            + "where d.demoDatasetKey is null and "
            + "((d.documentType = :type and d.title = :title and d.version = :version) "
            + "or (d.documentType = :type and d.title = :title and "
            + "((:supplierId is null and d.supplier is null) or d.supplier.id = :supplierId)) "
            + "or (d.documentType = :type and d.version = :version and "
            + "((:supplierId is null and d.supplier is null) or d.supplier.id = :supplierId)) "
            + "or (d.title = :title and d.version = :version and "
            + "((:supplierId is null and d.supplier is null) or d.supplier.id = :supplierId))) "
            + "order by d.id")
    List<DocumentMetadata> findPossibleLegacyDemoBaselines(@Param("type") DocumentType type,
                                                             @Param("title") String title,
                                                             @Param("version") String version,
                                                             @Param("supplierId") Long supplierId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DocumentMetadata d left join fetch d.supplier where d.id = :id")
    Optional<DocumentMetadata> findByIdForUpdateWithSupplier(@Param("id") Long id);
}

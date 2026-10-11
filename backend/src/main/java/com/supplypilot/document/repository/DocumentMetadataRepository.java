package com.supplypilot.document.repository;

import java.util.Optional;

import com.supplypilot.document.domain.DocumentMetadata;
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
}

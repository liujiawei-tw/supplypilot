package com.supplypilot.document.repository;

import com.supplypilot.document.domain.DocumentMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, Long>,
        JpaSpecificationExecutor<DocumentMetadata> {
}

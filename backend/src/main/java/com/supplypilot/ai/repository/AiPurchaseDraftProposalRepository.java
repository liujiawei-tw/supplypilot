package com.supplypilot.ai.repository;

import java.util.Optional;

import com.supplypilot.ai.domain.AiPurchaseDraftProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiPurchaseDraftProposalRepository extends JpaRepository<AiPurchaseDraftProposal, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AiPurchaseDraftProposal p where p.id = :id")
    Optional<AiPurchaseDraftProposal> lockById(@Param("id") String id);
}

package com.supplypilot.recommendation.repository;

import com.supplypilot.recommendation.domain.PurchaseRecommendation;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseRecommendationRepository extends JpaRepository<PurchaseRecommendation, Long> {

    @Override
    @EntityGraph(attributePaths = {"material", "recommendedSupplier", "supplierResults", "supplierResults.supplier"})
    Optional<PurchaseRecommendation> findById(Long id);
}

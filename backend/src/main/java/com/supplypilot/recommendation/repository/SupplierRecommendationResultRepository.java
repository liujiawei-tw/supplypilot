package com.supplypilot.recommendation.repository;

import com.supplypilot.recommendation.domain.SupplierRecommendationResult;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRecommendationResultRepository extends JpaRepository<SupplierRecommendationResult, Long> {

    @EntityGraph(attributePaths = "supplier")
    List<SupplierRecommendationResult> findByRecommendationIdOrderByRankAsc(Long recommendationId);
}

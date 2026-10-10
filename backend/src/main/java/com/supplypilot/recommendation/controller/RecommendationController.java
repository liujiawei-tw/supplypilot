package com.supplypilot.recommendation.controller;

import com.supplypilot.recommendation.dto.request.CreateRecommendationRequest;
import com.supplypilot.recommendation.dto.response.RecommendationResponse;
import com.supplypilot.recommendation.dto.response.SupplierRecommendationResultResponse;
import com.supplypilot.recommendation.service.PurchaseRecommendationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private final PurchaseRecommendationService purchaseRecommendationService;

    public RecommendationController(PurchaseRecommendationService purchaseRecommendationService) {
        this.purchaseRecommendationService = purchaseRecommendationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecommendationResponse createRecommendation(@Valid @RequestBody CreateRecommendationRequest request) {
        return purchaseRecommendationService.createRecommendation(request);
    }

    @GetMapping("/{id}")
    public RecommendationResponse getRecommendation(@PathVariable Long id) {
        return purchaseRecommendationService.getRecommendation(id);
    }

    @GetMapping("/{id}/suppliers")
    public List<SupplierRecommendationResultResponse> getSupplierResults(@PathVariable Long id) {
        return purchaseRecommendationService.getSupplierResults(id);
    }
}

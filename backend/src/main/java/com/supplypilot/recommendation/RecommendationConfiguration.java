package com.supplypilot.recommendation;

import com.supplypilot.recommendation.calculator.NetRequirementCalculator;
import com.supplypilot.recommendation.calculator.PurchaseQuantityCalculator;
import com.supplypilot.recommendation.ranking.SupplierConstraintEvaluator;
import com.supplypilot.recommendation.ranking.SupplierRankingService;
import com.supplypilot.recommendation.ranking.SupplierScoreCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RecommendationConfiguration {

    @Bean
    NetRequirementCalculator netRequirementCalculator() {
        return new NetRequirementCalculator();
    }

    @Bean
    PurchaseQuantityCalculator purchaseQuantityCalculator() {
        return new PurchaseQuantityCalculator();
    }

    @Bean
    SupplierConstraintEvaluator supplierConstraintEvaluator() {
        return new SupplierConstraintEvaluator();
    }

    @Bean
    SupplierScoreCalculator supplierScoreCalculator() {
        return new SupplierScoreCalculator();
    }

    @Bean
    SupplierRankingService supplierRankingService(
            PurchaseQuantityCalculator purchaseQuantityCalculator,
            SupplierConstraintEvaluator supplierConstraintEvaluator,
            SupplierScoreCalculator supplierScoreCalculator) {
        return new SupplierRankingService(
                purchaseQuantityCalculator, supplierConstraintEvaluator, supplierScoreCalculator);
    }
}

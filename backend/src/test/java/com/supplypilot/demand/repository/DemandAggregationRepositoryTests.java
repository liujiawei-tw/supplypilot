package com.supplypilot.demand.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.supplypilot.demand.domain.Demand;
import com.supplypilot.integration.AbstractIntegrationTest;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class DemandAggregationRepositoryTests extends AbstractIntegrationTest {
    @Autowired private DemandRepository demandRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void sumsOnlyActiveDemandForStandardScenarioE() {
        Material material = material("MAT-AGG-STANDARD");
        User user = user("demand-aggregation-standard");
        demand(material, user, "500", LocalDate.of(2026, 10, 20));
        demand(material, user, "300", LocalDate.of(2026, 10, 25));
        cancelledDemand(material, user, "400", LocalDate.of(2026, 10, 22));

        assertThat(demandRepository.sumActiveDemand(material.getId(), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 25)))
                .isEqualByComparingTo("800");
    }

    @Test
    void includesBothPeriodBoundariesAndExcludesDatesOutsideThePeriod() {
        Material material = material("MAT-AGG-BOUNDARY");
        User user = user("demand-aggregation-boundary");
        demand(material, user, "100", LocalDate.of(2026, 10, 7));
        demand(material, user, "200", LocalDate.of(2026, 10, 8));
        demand(material, user, "300", LocalDate.of(2026, 10, 20));
        demand(material, user, "400", LocalDate.of(2026, 10, 21));

        assertThat(demandRepository.sumActiveDemand(material.getId(), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 20)))
                .isEqualByComparingTo("500");
    }

    @Test
    void isolatesDemandByMaterial() {
        User user = user("demand-aggregation-material-isolation");
        Material materialA = material("MAT-AGG-ISOLATION-A");
        Material materialB = material("MAT-AGG-ISOLATION-B");
        demand(materialA, user, "500", LocalDate.of(2026, 10, 20));
        demand(materialB, user, "900", LocalDate.of(2026, 10, 20));

        assertThat(demandRepository.sumActiveDemand(materialA.getId(), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 25)))
                .isEqualByComparingTo("500");
    }

    @Test
    void returnsZeroWhenNoDemandMatchesTheMaterialPeriodOrStatus() {
        Material material = material("MAT-AGG-EMPTY");
        Material otherMaterial = material("MAT-AGG-EMPTY-OTHER");
        User user = user("demand-aggregation-empty");
        demand(otherMaterial, user, "100", LocalDate.of(2026, 10, 20));
        demand(material, user, "200", LocalDate.of(2026, 10, 7));
        cancelledDemand(material, user, "300", LocalDate.of(2026, 10, 20));

        assertThat(demandRepository.sumActiveDemand(material.getId(), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 20)))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void preservesDecimalPrecision() {
        Material material = material("MAT-AGG-PRECISION");
        User user = user("demand-aggregation-precision");
        demand(material, user, "0.1250", LocalDate.of(2026, 10, 20));
        demand(material, user, "0.3750", LocalDate.of(2026, 10, 20));

        assertThat(demandRepository.sumActiveDemand(material.getId(), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 20)))
                .isEqualByComparingTo("0.5000");
    }

    @Test
    void supportsASingleDayPeriod() {
        Material material = material("MAT-AGG-SINGLE-DAY");
        User user = user("demand-aggregation-single-day");
        demand(material, user, "100", LocalDate.of(2026, 10, 19));
        demand(material, user, "200", LocalDate.of(2026, 10, 20));
        demand(material, user, "300", LocalDate.of(2026, 10, 21));

        assertThat(demandRepository.sumActiveDemand(material.getId(), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20)))
                .isEqualByComparingTo("200");
    }

    private void demand(Material material, User user, String quantity, LocalDate requiredDate) {
        demandRepository.saveAndFlush(Demand.create(material, new BigDecimal(quantity), requiredDate, null, user, TestDataConstants.TEST_INSTANT));
    }

    private void cancelledDemand(Material material, User user, String quantity, LocalDate requiredDate) {
        Demand demand = Demand.create(material, new BigDecimal(quantity), requiredDate, null, user, TestDataConstants.TEST_INSTANT);
        demand.cancel(TestDataConstants.TEST_INSTANT);
        demandRepository.saveAndFlush(demand);
    }

    private Material material(String code) {
        return materialRepository.saveAndFlush(Material.create(code, "Tea", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT));
    }

    private User user(String username) {
        return userRepository.saveAndFlush(User.create(username, "hash", "Planner", UserRole.PRODUCTION_PLANNER, TestDataConstants.TEST_INSTANT));
    }
}

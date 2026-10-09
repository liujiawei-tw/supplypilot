package com.supplypilot.demand.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DemandTests {
    private final Material material = Material.create("MAT-001", "Tea", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT);
    private final User user = User.create("planner", "hash", "Planner", UserRole.PRODUCTION_PLANNER, TestDataConstants.TEST_INSTANT);

    @Test void createsActiveDemandAndPreservesRequiredValues() {
        Demand demand = Demand.create(material, new BigDecimal("400"), LocalDate.of(2026, 10, 8), "Promotion", user, TestDataConstants.TEST_INSTANT);
        assertThat(demand.getStatus()).isEqualTo(DemandStatus.ACTIVE);
        assertThat(demand.getQuantity()).isEqualByComparingTo("400");
        assertThat(demand.getCreatedAt()).isEqualTo(TestDataConstants.TEST_INSTANT);
        assertThat(demand.getUpdatedAt()).isEqualTo(TestDataConstants.TEST_INSTANT);
    }

    @Test void rejectsZeroAndNegativeQuantity() {
        assertThatThrownBy(() -> Demand.create(material, BigDecimal.ZERO, LocalDate.of(2026, 10, 8), null, user, TestDataConstants.TEST_INSTANT)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThatThrownBy(() -> Demand.create(material, new BigDecimal("-1"), LocalDate.of(2026, 10, 8), null, user, TestDataConstants.TEST_INSTANT)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
    }

    @Test void updatesActiveDemandWithoutChangingTimestampForSameValues() {
        Demand demand = Demand.create(material, new BigDecimal("400"), LocalDate.of(2026, 10, 9), "Old", user, TestDataConstants.TEST_INSTANT);
        demand.update(new BigDecimal("400"), LocalDate.of(2026, 10, 9), "Old", true, LocalDate.of(2026, 10, 8), TestDataConstants.TEST_INSTANT.plusSeconds(60));
        assertThat(demand.getUpdatedAt()).isEqualTo(TestDataConstants.TEST_INSTANT);
        demand.update(new BigDecimal("500"), LocalDate.of(2026, 10, 10), null, true, LocalDate.of(2026, 10, 8), TestDataConstants.TEST_INSTANT.plusSeconds(60));
        assertThat(demand.getQuantity()).isEqualByComparingTo("500");
        assertThat(demand.getNote()).isNull();
        assertThat(demand.getUpdatedAt()).isEqualTo(TestDataConstants.TEST_INSTANT.plusSeconds(60));
    }

    @Test void cancelsOnlyActiveDemand() {
        Demand demand = Demand.create(material, BigDecimal.ONE, LocalDate.of(2026, 10, 8), null, user, TestDataConstants.TEST_INSTANT);
        demand.cancel(TestDataConstants.TEST_INSTANT.plusSeconds(1));
        assertThat(demand.getStatus()).isEqualTo(DemandStatus.CANCELLED);
        assertThatThrownBy(() -> demand.cancel(TestDataConstants.TEST_INSTANT.plusSeconds(2))).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.DEMAND_NOT_ACTIVE);
        assertThatThrownBy(() -> demand.update(BigDecimal.TEN, null, null, false, LocalDate.of(2026, 10, 8), TestDataConstants.TEST_INSTANT)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.DEMAND_NOT_ACTIVE);
    }
}

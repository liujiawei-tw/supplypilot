package com.supplypilot.demand.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.demand.domain.Demand;
import com.supplypilot.demand.domain.DemandStatus;
import com.supplypilot.demand.dto.request.CreateDemandRequest;
import com.supplypilot.demand.dto.request.UpdateDemandRequest;
import com.supplypilot.demand.mapper.DemandMapper;
import com.supplypilot.demand.repository.DemandRepository;
import com.supplypilot.material.domain.*;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.testsupport.TestDataConstants;
import com.supplypilot.user.domain.*;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DemandServiceTests {
    @Mock DemandRepository demandRepository; @Mock MaterialRepository materialRepository; @Mock UserRepository userRepository; @Mock CurrentUserProvider currentUserProvider;
    private DemandService service;
    private Material activeMaterial; private User planner;
    @BeforeEach void setUp() {
        service = new DemandService(demandRepository, materialRepository, userRepository, currentUserProvider, new DemandMapper(), Clock.fixed(TestDataConstants.TEST_INSTANT, ZoneOffset.UTC));
        activeMaterial = Material.create("MAT-001", "Tea", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT);
        planner = User.create("planner", "hash", "Planner", UserRole.PRODUCTION_PLANNER, TestDataConstants.TEST_INSTANT);
    }
    @Test void createsDemandUsingAuthenticatedUser() {
        when(materialRepository.findById(1L)).thenReturn(Optional.of(activeMaterial)); when(currentUserProvider.getCurrentUserId()).thenReturn(9L); when(userRepository.findById(9L)).thenReturn(Optional.of(planner)); when(demandRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        service.createDemand(new CreateDemandRequest(1L, BigDecimal.TEN, LocalDate.of(2026, 10, 8), null));
        ArgumentCaptor<Demand> captor = ArgumentCaptor.forClass(Demand.class); verify(demandRepository).save(captor.capture()); assertThat(captor.getValue().getCreatedBy()).isSameAs(planner);
    }
    @Test void rejectsMissingInactiveAndPastCreateInputs() {
        when(materialRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createDemand(new CreateDemandRequest(1L, BigDecimal.ONE, LocalDate.of(2026,10,8), null))).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.MATERIAL_NOT_FOUND);
        Material inactive = Material.create("MAT-2", "Inactive", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT); inactive.update(null, null, MaterialStatus.INACTIVE, TestDataConstants.TEST_INSTANT);
        when(materialRepository.findById(2L)).thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.createDemand(new CreateDemandRequest(2L, BigDecimal.ONE, LocalDate.of(2026,10,8), null))).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.MATERIAL_INACTIVE);
        assertThatThrownBy(() -> service.createDemand(new CreateDemandRequest(1L, BigDecimal.ONE, LocalDate.of(2026,10,7), null))).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_DATE_RANGE);
    }
    @Test void rejectsMissingDemandAndPastOrCancelledUpdates() {
        when(demandRepository.findById(99L)).thenReturn(Optional.empty()); assertThatThrownBy(() -> service.getDemandById(99L)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.DEMAND_NOT_FOUND);
        Demand past = Demand.create(activeMaterial, BigDecimal.ONE, LocalDate.of(2026,10,7), null, planner, TestDataConstants.TEST_INSTANT); when(demandRepository.findById(1L)).thenReturn(Optional.of(past));
        assertThatThrownBy(() -> service.updateDemand(1L, new UpdateDemandRequest())).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_DATE_RANGE);
        Demand cancelled = Demand.create(activeMaterial, BigDecimal.ONE, LocalDate.of(2026,10,8), null, planner, TestDataConstants.TEST_INSTANT); cancelled.cancel(TestDataConstants.TEST_INSTANT); when(demandRepository.findById(2L)).thenReturn(Optional.of(cancelled));
        assertThatThrownBy(() -> service.cancelDemand(2L)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.DEMAND_NOT_ACTIVE);
    }

    @Test void rejectsInvalidUpdateWithoutMutatingPersistedDemandState() {
        Demand demand = Demand.create(activeMaterial, new BigDecimal("10"), LocalDate.of(2026, 10, 9), "Original", planner, TestDataConstants.TEST_INSTANT);
        when(demandRepository.findById(1L)).thenReturn(Optional.of(demand));
        UpdateDemandRequest request = new UpdateDemandRequest(); request.setQuantity(BigDecimal.ZERO); request.setRequiredDate(LocalDate.of(2026, 10, 7)); request.setNote("Changed");
        assertThatThrownBy(() -> service.updateDemand(1L, request)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThat(demand.getQuantity()).isEqualByComparingTo("10"); assertThat(demand.getRequiredDate()).isEqualTo(LocalDate.of(2026, 10, 9)); assertThat(demand.getNote()).isEqualTo("Original");
        verify(demandRepository, never()).save(any());
    }

    @Test void buildsListSpecificationForNoAndPartialFilters() {
        when(demandRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class))).thenReturn(Page.empty());
        PageRequest pageable = PageRequest.of(0, 20);
        service.listDemands(null, null, null, null, pageable);
        service.listDemands(1L, null, null, null, pageable);
        service.listDemands(null, DemandStatus.ACTIVE, null, null, pageable);
        service.listDemands(null, null, LocalDate.of(2026, 10, 8), null, pageable);
        service.listDemands(null, null, null, LocalDate.of(2026, 10, 9), pageable);
        verify(demandRepository, times(5)).findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class));
    }

    @Test void sumsActiveDemandForAValidPeriodWithoutWritingDemand() {
        when(demandRepository.sumActiveDemand(1L, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 20)))
                .thenReturn(new BigDecimal("0.5000"));

        BigDecimal result = service.sumActiveDemand(1L, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 20));

        assertThat(result).isEqualByComparingTo("0.5000");
        verify(demandRepository).sumActiveDemand(1L, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 20));
        verify(demandRepository, never()).save(any());
        verify(demandRepository, never()).delete(any(Demand.class));
    }

    @Test void acceptsASingleDayPeriod() {
        LocalDate date = LocalDate.of(2026, 10, 20);
        when(demandRepository.sumActiveDemand(1L, date, date)).thenReturn(BigDecimal.TEN);

        assertThat(service.sumActiveDemand(1L, date, date)).isEqualByComparingTo("10");
    }

    @Test void rejectsNullAndReversedAggregationPeriodsWithoutCallingRepository() {
        assertThatThrownBy(() -> service.sumActiveDemand(1L, null, LocalDate.of(2026, 10, 20)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_DATE_RANGE);
        assertThatThrownBy(() -> service.sumActiveDemand(1L, LocalDate.of(2026, 10, 8), null))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_DATE_RANGE);
        assertThatThrownBy(() -> service.sumActiveDemand(1L, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 20)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_DATE_RANGE);
        verifyNoInteractions(demandRepository);
    }
}

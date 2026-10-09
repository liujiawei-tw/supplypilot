package com.supplypilot.demand.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.supplypilot.demand.domain.Demand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DemandRepository extends JpaRepository<Demand, Long>, JpaSpecificationExecutor<Demand> {

    @Query("""
            select coalesce(sum(d.quantity), 0)
            from Demand d
            where d.material.id = :materialId
              and d.status = com.supplypilot.demand.domain.DemandStatus.ACTIVE
              and d.requiredDate >= :periodStart
              and d.requiredDate <= :periodEnd
            """)
    BigDecimal sumActiveDemand(
            @Param("materialId") Long materialId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd);
}

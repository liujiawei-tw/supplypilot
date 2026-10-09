package com.supplypilot.demand.repository;

import com.supplypilot.demand.domain.Demand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DemandRepository extends JpaRepository<Demand, Long>, JpaSpecificationExecutor<Demand> { }

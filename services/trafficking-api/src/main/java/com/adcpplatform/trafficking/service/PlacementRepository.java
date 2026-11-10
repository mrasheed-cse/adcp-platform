package com.adcpplatform.trafficking.service;

import org.springframework.data.jpa.repository.JpaRepository;

import com.adcpplatform.trafficking.domain.Placement;

public interface PlacementRepository extends JpaRepository<Placement, String> {}

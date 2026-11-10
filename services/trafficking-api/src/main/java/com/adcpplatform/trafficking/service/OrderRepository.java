package com.adcpplatform.trafficking.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.adcpplatform.trafficking.domain.AdOrder;

public interface OrderRepository extends JpaRepository<AdOrder, String> {
    List<AdOrder> findByAdvertiserId(String advertiserId);

    Optional<AdOrder> findByExternalRef(String externalRef);
}

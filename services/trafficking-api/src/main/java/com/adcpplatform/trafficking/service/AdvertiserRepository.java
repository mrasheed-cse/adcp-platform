package com.adcpplatform.trafficking.service;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.adcpplatform.trafficking.domain.Advertiser;

public interface AdvertiserRepository extends JpaRepository<Advertiser, String> {
    Optional<Advertiser> findByExternalRef(String externalRef);
}

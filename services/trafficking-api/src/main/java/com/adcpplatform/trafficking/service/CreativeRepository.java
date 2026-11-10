package com.adcpplatform.trafficking.service;

import java.util.Optional;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.adcpplatform.trafficking.domain.Creative;

public interface CreativeRepository extends JpaRepository<Creative, String> {
    List<Creative> findByAdvertiserId(String advertiserId);

    Optional<Creative> findByExternalRef(String externalRef);
}

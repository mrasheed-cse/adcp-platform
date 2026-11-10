package com.adcpplatform.trafficking.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.adcpplatform.trafficking.domain.LineItem;
import com.adcpplatform.trafficking.domain.LineItemStatus;

public interface LineItemRepository extends JpaRepository<LineItem, String> {

    /** One query for the snapshot: line items with their order and creatives, no lazy loading. */
    @Query("select distinct li from LineItem li join fetch li.order left join fetch li.creatives where li.status = :status")
    List<LineItem> findWithOrderAndCreativesByStatus(@Param("status") LineItemStatus status);

    @Query("select distinct li from LineItem li join fetch li.order left join fetch li.creatives where li.id = :id")
    Optional<LineItem> findDetailed(@Param("id") String id);

    @Query("select distinct li from LineItem li join fetch li.order left join fetch li.creatives order by li.id")
    List<LineItem> findAllDetailed();

    @Query("select distinct li from LineItem li join fetch li.order left join fetch li.creatives where li.externalRef = :ref")
    Optional<LineItem> findDetailedByExternalRef(@Param("ref") String externalRef);
}

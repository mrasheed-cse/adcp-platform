package com.adcpplatform.trafficking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** An order groups line items for one advertiser. ("order" is reserved in SQL, hence ad_order.) */
@Entity
@Table(name = "ad_order")
public class AdOrder {
    @Id
    private String id;
    @Column(name = "advertiser_id")
    private String advertiserId;
    private String name;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    @Column(name = "external_ref")
    private String externalRef;
    @Version
    private long version;

    protected AdOrder() {}

    public AdOrder(String id, String advertiserId, String name) {
        this.id = id;
        this.advertiserId = advertiserId;
        this.name = name;
        this.status = OrderStatus.ACTIVE;
    }

    public String getId() { return id; }
    public String getAdvertiserId() { return advertiserId; }
    public String getName() { return name; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public long getVersion() { return version; }

    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
}

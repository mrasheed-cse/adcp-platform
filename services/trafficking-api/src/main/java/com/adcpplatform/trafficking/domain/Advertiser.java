package com.adcpplatform.trafficking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "advertiser")
public class Advertiser {
    @Id
    private String id;
    private String name;
    @Column(name = "external_ref")
    private String externalRef;

    protected Advertiser() {}

    public Advertiser(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }

    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
}

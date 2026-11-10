package com.adcpplatform.trafficking.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An ad slot on a publisher's page or app; the id is what the ad tag sends as "placement". */
@Entity
@Table(name = "placement")
public class Placement {
    @Id
    private String id;
    private String name;

    protected Placement() {}

    public Placement(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }
}

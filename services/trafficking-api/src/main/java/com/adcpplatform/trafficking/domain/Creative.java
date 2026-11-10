package com.adcpplatform.trafficking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "creative")
public class Creative {
    @Id
    private String id;
    @Column(name = "advertiser_id")
    private String advertiserId;
    private String name;
    @Enumerated(EnumType.STRING)
    private CreativeType type;
    private int width;
    private int height;
    @Column(name = "image_url")
    private String imageUrl;
    private String html;
    @Column(name = "landing_url")
    private String landingUrl;
    @Column(name = "external_ref")
    private String externalRef;

    protected Creative() {}

    public Creative(String id, String advertiserId, String name, CreativeType type, int width, int height,
                    String imageUrl, String html, String landingUrl) {
        this.id = id;
        this.advertiserId = advertiserId;
        this.name = name;
        this.type = type;
        this.width = width;
        this.height = height;
        this.imageUrl = imageUrl;
        this.html = html;
        this.landingUrl = landingUrl;
    }

    public String getId() { return id; }
    public String getAdvertiserId() { return advertiserId; }
    public String getName() { return name; }
    public CreativeType getType() { return type; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getImageUrl() { return imageUrl; }
    public String getHtml() { return html; }
    public String getLandingUrl() { return landingUrl; }

    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
}

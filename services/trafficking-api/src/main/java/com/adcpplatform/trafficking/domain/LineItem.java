package com.adcpplatform.trafficking.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "line_item")
public class LineItem {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private AdOrder order;

    private String name;
    @Enumerated(EnumType.STRING)
    private LineItemStatus status;
    @Column(name = "external_ref")
    private String externalRef;
    private int priority;
    private BigDecimal cpm;
    @Column(name = "start_time")
    private Instant start;
    @Column(name = "end_time")
    private Instant end;
    @Column(name = "goal_impressions")
    private long goalImpressions;
    @Column(name = "freq_max")
    private Integer freqMax;
    @Column(name = "freq_window_seconds")
    private Integer freqWindowSeconds;
    @Convert(converter = StringListConverter.class)
    private List<String> countries = new ArrayList<>();
    @Convert(converter = StringListConverter.class)
    private List<String> devices = new ArrayList<>();
    @Convert(converter = StringListConverter.class)
    private List<String> placements = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "line_item_creative",
            joinColumns = @JoinColumn(name = "line_item_id"),
            inverseJoinColumns = @JoinColumn(name = "creative_id"))
    private Set<Creative> creatives = new LinkedHashSet<>();

    @Version
    private long version;
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected LineItem() {}

    public LineItem(String id, AdOrder order) {
        this.id = id;
        this.order = order;
        this.status = LineItemStatus.DRAFT;
    }

    /** Replaces every editable field at once; validated beforehand by LineItemRules. */
    public void apply(LineItemFields f, Instant now) {
        this.name = f.name();
        this.priority = f.priority();
        this.cpm = f.cpm();
        this.start = f.start();
        this.end = f.end();
        this.goalImpressions = f.goalImpressions();
        this.freqMax = f.freqMax();
        this.freqWindowSeconds = f.freqWindowSeconds();
        this.countries = new ArrayList<>(f.countries().stream().map(String::toUpperCase).toList());
        this.devices = new ArrayList<>(f.devices());
        this.placements = new ArrayList<>(f.placements());
        this.updatedAt = now;
    }

    public LineItemFields fields() {
        return new LineItemFields(name, priority, cpm, start, end, goalImpressions, freqMax, freqWindowSeconds,
                countries, devices, placements);
    }

    public String getId() { return id; }
    public AdOrder getOrder() { return order; }
    public String getName() { return name; }
    public LineItemStatus getStatus() { return status; }
    public void setStatus(LineItemStatus status, Instant now) { this.status = status; this.updatedAt = now; }
    public int getPriority() { return priority; }
    public BigDecimal getCpm() { return cpm; }
    public Instant getStart() { return start; }
    public Instant getEnd() { return end; }
    public long getGoalImpressions() { return goalImpressions; }
    public Integer getFreqMax() { return freqMax; }
    public Integer getFreqWindowSeconds() { return freqWindowSeconds; }
    public List<String> getCountries() { return countries; }
    public List<String> getDevices() { return devices; }
    public List<String> getPlacements() { return placements; }
    public Set<Creative> getCreatives() { return creatives; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }

    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
}

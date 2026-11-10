package com.adcpplatform.trafficking.snapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire format of contracts/line-item-snapshot.schema.json. Names are explicit so the contract does
 * not depend on the API's global naming strategy.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotJson(
        @JsonProperty("version") long version,
        @JsonProperty("generated_at") Instant generatedAt,
        @JsonProperty("line_items") List<LineItemJson> lineItems) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LineItemJson(
            @JsonProperty("id") String id,
            @JsonProperty("order_id") String orderId,
            @JsonProperty("advertiser_id") String advertiserId,
            @JsonProperty("name") String name,
            @JsonProperty("priority") int priority,
            @JsonProperty("cpm") BigDecimal cpm,
            @JsonProperty("start") Instant start,
            @JsonProperty("end") Instant end,
            @JsonProperty("placements") List<String> placements,
            @JsonProperty("targeting") TargetingJson targeting,
            @JsonProperty("goal") GoalJson goal,
            @JsonProperty("frequency_cap") FrequencyCapJson frequencyCap,
            @JsonProperty("creatives") List<CreativeJson> creatives) {}

    public record TargetingJson(
            @JsonProperty("countries") List<String> countries,
            @JsonProperty("devices") List<String> devices) {}

    public record GoalJson(@JsonProperty("impressions") long impressions) {}

    public record FrequencyCapJson(
            @JsonProperty("max") long max,
            @JsonProperty("window_seconds") long windowSeconds) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreativeJson(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("width") int width,
            @JsonProperty("height") int height,
            @JsonProperty("image_url") String imageUrl,
            @JsonProperty("html") String html,
            @JsonProperty("landing_url") String landingUrl) {}
}

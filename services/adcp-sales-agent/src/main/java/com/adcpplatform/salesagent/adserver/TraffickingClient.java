package com.adcpplatform.salesagent.adserver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The ad server's trafficking API, as the sales agent needs it. Every create is idempotent through an
 * external reference, so a retried sync never creates duplicates.
 */
public interface TraffickingClient {

    record LineItemSpec(String orderId, String name, int priority, BigDecimal cpm, Instant start, Instant end,
                        long goalImpressions, List<String> countries, List<String> placements, String externalRef) {}

    record CreativeSpec(String advertiserId, String name, String type, int width, int height, String imageUrl,
                        String html, String landingUrl, String externalRef) {}

    String ensureAdvertiser(String name, String externalRef);

    void ensurePlacement(String placementId, String name);

    String ensureOrder(String advertiserId, String name, String externalRef);

    /** status: active, paused or archived. */
    void setOrderStatus(String orderId, String status);

    String ensureLineItem(LineItemSpec spec);

    /** status: active, paused or archived. */
    void setLineItemStatus(String lineItemId, String status);

    String ensureCreative(CreativeSpec spec);

    void attachCreative(String lineItemId, String creativeId);

    long delivered(String lineItemId);
}

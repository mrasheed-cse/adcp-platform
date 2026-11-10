package com.adcpplatform.trafficking.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.adcpplatform.trafficking.domain.AdOrder;
import com.adcpplatform.trafficking.domain.Advertiser;
import com.adcpplatform.trafficking.domain.Creative;
import com.adcpplatform.trafficking.domain.CreativeType;
import com.adcpplatform.trafficking.domain.LineItem;
import com.adcpplatform.trafficking.domain.LineItemFields;
import com.adcpplatform.trafficking.domain.Placement;
import com.adcpplatform.trafficking.rules.ValidationException;

/** Request and response bodies. JSON is snake_case (spring.jackson.property-naming-strategy). */
public final class Dtos {

    private Dtos() {}

    public record NameRequest(String name, String externalRef) {}

    public record AdvertiserResponse(String id, String name, String externalRef) {
        static AdvertiserResponse of(Advertiser a) { return new AdvertiserResponse(a.getId(), a.getName(), a.getExternalRef()); }
    }

    public record OrderRequest(String advertiserId, String name, String externalRef) {}

    public record OrderResponse(String id, String advertiserId, String name, String status, long version, String externalRef) {
        static OrderResponse of(AdOrder o) {
            return new OrderResponse(o.getId(), o.getAdvertiserId(), o.getName(), lower(o.getStatus().name()), o.getVersion(),
                    o.getExternalRef());
        }
    }

    public record StatusRequest(String status, Long version) {}

    public record PlacementRequest(String id, String name) {}

    public record PlacementResponse(String id, String name) {
        static PlacementResponse of(Placement p) { return new PlacementResponse(p.getId(), p.getName()); }
    }

    public record CreativeRequest(String advertiserId, String name, String type, Integer width, Integer height,
                                  String imageUrl, String html, String landingUrl, String externalRef) {
        CreativeType parsedType() {
            if (type == null) {
                return null;
            }
            try {
                return CreativeType.valueOf(type.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("type", "must be image or html");
            }
        }
    }

    public record CreativeResponse(String id, String advertiserId, String name, String type, int width, int height,
                                   String imageUrl, String html, String landingUrl, String externalRef) {
        static CreativeResponse of(Creative c) {
            return new CreativeResponse(c.getId(), c.getAdvertiserId(), c.getName(), lower(c.getType().name()),
                    c.getWidth(), c.getHeight(), c.getImageUrl(), c.getHtml(), c.getLandingUrl(), c.getExternalRef());
        }
    }

    public record FrequencyCap(Integer max, Integer windowSeconds) {}

    public record LineItemRequest(String orderId, String name, Integer priority, BigDecimal cpm, Instant start,
                                  Instant end, Long goalImpressions, FrequencyCap frequencyCap,
                                  List<String> countries, List<String> devices, List<String> placements,
                                  Long version, String externalRef) {
        LineItemFields fields() {
            return new LineItemFields(name, priority == null ? 0 : priority, cpm, start, end,
                    goalImpressions == null ? 0 : goalImpressions,
                    frequencyCap == null ? null : frequencyCap.max(),
                    frequencyCap == null ? null : frequencyCap.windowSeconds(),
                    countries, devices, placements);
        }
    }

    public record LineItemResponse(String id, String orderId, String advertiserId, String name, String status,
                                   int priority, BigDecimal cpm, Instant start, Instant end, long goalImpressions,
                                   FrequencyCap frequencyCap, List<String> countries, List<String> devices,
                                   List<String> placements, List<String> creativeIds, long version,
                                   Instant updatedAt, String externalRef) {
        static LineItemResponse of(LineItem li) {
            FrequencyCap fc = li.getFreqMax() == null ? null : new FrequencyCap(li.getFreqMax(), li.getFreqWindowSeconds());
            List<String> creativeIds = li.getCreatives().stream().map(Creative::getId).sorted(Comparator.naturalOrder()).toList();
            return new LineItemResponse(li.getId(), li.getOrder().getId(), li.getOrder().getAdvertiserId(), li.getName(),
                    lower(li.getStatus().name()), li.getPriority(), li.getCpm(), li.getStart(), li.getEnd(),
                    li.getGoalImpressions(), fc, li.getCountries(), li.getDevices(), li.getPlacements(), creativeIds,
                    li.getVersion(), li.getUpdatedAt(), li.getExternalRef());
        }
    }

    public record DeliveryResponse(String lineItemId, long goalImpressions, long delivered, Double progress) {}

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}

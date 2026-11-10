package com.adcpplatform.trafficking.snapshot;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.adcpplatform.trafficking.domain.Creative;
import com.adcpplatform.trafficking.domain.LineItem;
import com.adcpplatform.trafficking.domain.LineItemStatus;
import com.adcpplatform.trafficking.domain.OrderStatus;
import com.adcpplatform.trafficking.snapshot.SnapshotJson.CreativeJson;
import com.adcpplatform.trafficking.snapshot.SnapshotJson.FrequencyCapJson;
import com.adcpplatform.trafficking.snapshot.SnapshotJson.GoalJson;
import com.adcpplatform.trafficking.snapshot.SnapshotJson.LineItemJson;
import com.adcpplatform.trafficking.snapshot.SnapshotJson.TargetingJson;

/** Turns line items into the snapshot the decision engine serves from. Pure function. */
public final class SnapshotBuilder {

    private SnapshotBuilder() {}

    /** A line item is servable when it and its order are active, its flight has not ended, and it has creatives. */
    public static boolean servable(LineItem li, Instant now) {
        return li.getStatus() == LineItemStatus.ACTIVE
                && li.getOrder().getStatus() == OrderStatus.ACTIVE
                && li.getEnd().isAfter(now)
                && !li.getCreatives().isEmpty();
    }

    public static SnapshotJson build(Collection<LineItem> lineItems, long version, Instant now) {
        List<LineItemJson> items = lineItems.stream()
                .filter(li -> servable(li, now))
                .sorted(Comparator.comparing(LineItem::getId))
                .map(SnapshotBuilder::toJson)
                .toList();
        return new SnapshotJson(version, now, items);
    }

    static LineItemJson toJson(LineItem li) {
        FrequencyCapJson fc = li.getFreqMax() == null ? null
                : new FrequencyCapJson(li.getFreqMax(), li.getFreqWindowSeconds());
        List<CreativeJson> creatives = li.getCreatives().stream()
                .sorted(Comparator.comparing(Creative::getId))
                .map(c -> new CreativeJson(c.getId(), c.getType().name().toLowerCase(Locale.ROOT), c.getWidth(),
                        c.getHeight(), c.getImageUrl(), c.getHtml(), c.getLandingUrl()))
                .toList();
        return new LineItemJson(
                li.getId(), li.getOrder().getId(), li.getOrder().getAdvertiserId(), li.getName(), li.getPriority(),
                li.getCpm(), li.getStart(), li.getEnd(), List.copyOf(li.getPlacements()),
                new TargetingJson(List.copyOf(li.getCountries()), List.copyOf(li.getDevices())),
                new GoalJson(li.getGoalImpressions()), fc, creatives);
    }
}

package com.adcpplatform.salesagent.adserver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.adcpplatform.salesagent.adserver.TraffickingClient.CreativeSpec;
import com.adcpplatform.salesagent.adserver.TraffickingClient.LineItemSpec;
import com.adcpplatform.salesagent.catalog.ProductCatalog;
import com.adcpplatform.salesagent.creative.CreativeRepository;
import com.adcpplatform.salesagent.mediabuy.MediaBuyRepository;
import com.adcpplatform.salesagent.mediabuy.MediaBuyService;
import com.adcpplatform.salesagent.model.Models.CreativeFormat;
import com.adcpplatform.salesagent.model.Models.DeliveryType;
import com.adcpplatform.salesagent.model.Models.MediaBuy;
import com.adcpplatform.salesagent.model.Models.MediaBuyPackage;
import com.adcpplatform.salesagent.model.Models.MediaBuyStatus;
import com.adcpplatform.salesagent.model.Models.Product;
import com.adcpplatform.salesagent.model.Models.Provisioning;
import com.adcpplatform.salesagent.model.Models.SyncedCreative;

/**
 * Makes the ad server match a media buy: advertiser, order, one line item per package, creatives attached,
 * line items with creatives activated, and the order's status following the buy's status.
 *
 * Every step is idempotent (external references in the ad server), so a sync can be retried from the top
 * after any failure. The media buy in this service is the desired state; the ad server follows it.
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private static final int PRIORITY_STANDARD = 8;        // guaranteed packages
    private static final int PRIORITY_PRICE_PRIORITY = 12; // non-guaranteed packages

    private final MediaBuyRepository buys;
    private final MediaBuyService service;
    private final CreativeRepository creatives;
    private final ProductCatalog catalog;
    private final TraffickingClient adServer;

    public Reconciler(MediaBuyRepository buys, MediaBuyService service, CreativeRepository creatives,
                      ProductCatalog catalog, TraffickingClient adServer) {
        this.buys = buys;
        this.service = service;
        this.creatives = creatives;
        this.catalog = catalog;
        this.adServer = adServer;
    }

    /** Never throws for ad-server problems: the outcome is recorded on the media buy. */
    public MediaBuy reconcile(String mediaBuyId) {
        MediaBuy buy = buys.findById(mediaBuyId).orElseThrow(() -> new IllegalArgumentException("no media buy " + mediaBuyId));
        long seenVersion = buy.version();
        String advertiserId = buy.traffickingAdvertiserId();
        String orderId = buy.traffickingOrderId();
        List<MediaBuyPackage> packages = new ArrayList<>(buy.packages());
        try {
            advertiserId = adServer.ensureAdvertiser(buy.brand(), "adcp:" + buy.accountId() + ":" + buy.brand());
            orderId = adServer.ensureOrder(advertiserId, "AdCP " + buy.brand() + " " + buy.mediaBuyId(), "adcp:" + buy.mediaBuyId());
            Set<String> placementsEnsured = new HashSet<>();
            for (int i = 0; i < packages.size(); i++) {
                MediaBuyPackage p = packages.get(i);
                Product product = catalog.find(p.productId())
                        .orElseThrow(() -> new TraffickingException("product " + p.productId() + " no longer exists", true));
                for (String placement : product.placements()) {
                    if (placementsEnsured.add(placement)) {
                        adServer.ensurePlacement(placement, product.name() + " (" + placement + ")");
                    }
                }
                String lineItemId = adServer.ensureLineItem(new LineItemSpec(orderId,
                        "AdCP " + buy.mediaBuyId() + " / " + p.packageId() + " / " + product.name(),
                        product.deliveryType() == DeliveryType.GUARANTEED ? PRIORITY_STANDARD : PRIORITY_PRICE_PRIORITY,
                        p.rate(), buy.startTime(), buy.endTime(), p.impressionGoal(), product.countries(),
                        product.placements(), "adcp:" + buy.mediaBuyId() + ":" + p.packageId()));
                packages.set(i, p.withLineItemId(lineItemId));

                for (String creativeId : p.creativeIds()) {
                    SyncedCreative c = creatives.find(buy.accountId(), creativeId)
                            .orElseThrow(() -> new TraffickingException("creative " + creativeId + " is missing", true));
                    String adServerCreativeId = c.traffickingCreativeId();
                    if (adServerCreativeId == null) {
                        adServerCreativeId = adServer.ensureCreative(creativeSpec(advertiserId, c));
                        creatives.setTraffickingId(buy.accountId(), creativeId, adServerCreativeId);
                    }
                    adServer.attachCreative(lineItemId, adServerCreativeId);
                }
                if (!p.creativeIds().isEmpty() && servable(buy)) {
                    adServer.setLineItemStatus(lineItemId, "active");
                }
            }
            adServer.setOrderStatus(orderId, orderStatus(buy.status()));
            return record(mediaBuyId, seenVersion, Provisioning.PROVISIONED, null, false, advertiserId, orderId, packages);
        } catch (TraffickingException e) {
            log.warn("sync of media buy {} failed (permanent={}): {}", mediaBuyId, e.permanent(), e.getMessage());
            Provisioning state = e.permanent() ? Provisioning.FAILED : Provisioning.PENDING;
            return record(mediaBuyId, seenVersion, state, e.getMessage(), !e.permanent(), advertiserId, orderId, packages);
        }
    }

    /**
     * Saves the sync result onto the latest version of the buy. The buyer may have changed it meanwhile
     * (new creatives, a pause): their fields win, and the buy stays dirty so the next run picks the change up.
     */
    private MediaBuy record(String id, long seenVersion, Provisioning state, String error, boolean retry,
                            String advertiserId, String orderId, List<MediaBuyPackage> synced) {
        Map<String, String> lineItems = synced.stream().filter(p -> p.lineItemId() != null)
                .collect(Collectors.toMap(MediaBuyPackage::packageId, MediaBuyPackage::lineItemId));
        return service.modify(id, current -> {
            List<MediaBuyPackage> merged = current.packages().stream()
                    .map(p -> p.lineItemId() == null && lineItems.containsKey(p.packageId())
                            ? p.withLineItemId(lineItems.get(p.packageId())) : p)
                    .toList();
            boolean stillDirty = retry || current.version() != seenVersion;
            return current.withSync(state, error, stillDirty, advertiserId, orderId, merged, service.now());
        });
    }

    private boolean servable(MediaBuy buy) {
        return (buy.status() == MediaBuyStatus.ACTIVE || buy.status() == MediaBuyStatus.PENDING_ACTIVATION
                || buy.status() == MediaBuyStatus.PAUSED) && buy.endTime().isAfter(service.now());
    }

    static String orderStatus(MediaBuyStatus s) {
        return switch (s) {
            case PENDING_ACTIVATION, ACTIVE -> "active"; // the line item's flight dates hold back a future start
            case PAUSED -> "paused";
            case CANCELED, COMPLETED -> "archived";
        };
    }

    private CreativeSpec creativeSpec(String advertiserId, SyncedCreative c) {
        CreativeFormat f = catalog.format(c.formatId())
                .orElseThrow(() -> new TraffickingException("unknown format " + c.formatId(), true));
        boolean image = c.imageUrl() != null && !c.imageUrl().isBlank();
        int width = f.width() == null ? 300 : f.width();     // native units render in a 300x250 slot by default
        int height = f.height() == null ? 250 : f.height();
        return new CreativeSpec(advertiserId, c.name(), image ? "image" : "html", width, height,
                image ? c.imageUrl() : null, image ? null : c.html(), c.clickUrl(), "adcp:" + c.accountId() + ":" + c.creativeId());
    }

}

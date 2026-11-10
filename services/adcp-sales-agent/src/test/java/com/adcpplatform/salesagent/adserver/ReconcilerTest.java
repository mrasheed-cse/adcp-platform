package com.adcpplatform.salesagent.adserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.adcpplatform.salesagent.Fixture;
import com.adcpplatform.salesagent.adserver.TraffickingClient.LineItemSpec;
import com.adcpplatform.salesagent.model.Models.CreativeInput;
import com.adcpplatform.salesagent.model.Models.MediaBuy;
import com.adcpplatform.salesagent.model.Models.MediaBuyPackage;
import com.adcpplatform.salesagent.model.Models.MediaBuyStatus;
import com.adcpplatform.salesagent.model.Models.Provisioning;

class ReconcilerTest {

    private final Fixture f = new Fixture();
    private static final String KEY = "acme-news-edu-2026-11-0001";

    @Test
    void newBuyBecomesOrderWithOneDraftLineItemPerPackage() {
        MediaBuy buy = f.book(KEY);
        assertTrue(buy.dirty());
        assertEquals(Provisioning.PENDING, buy.provisioning());

        MediaBuy synced = f.reconciler.reconcile(buy.mediaBuyId());
        assertEquals(Provisioning.PROVISIONED, synced.provisioning());
        assertFalse(synced.dirty());
        assertNull(synced.provisioningError());
        assertNotNull(synced.traffickingOrderId());
        assertEquals("active", f.adServer.orderStatus.get(synced.traffickingOrderId()));
        assertEquals(List.of("news_top", "news_sidebar", "jobs_feed"), List.copyOf(f.adServer.placements));

        MediaBuyPackage news = synced.packages().get(0);
        LineItemSpec newsLi = f.adServer.lineItems.get(news.lineItemId());
        assertEquals(12, newsLi.priority());                        // non-guaranteed -> price priority
        assertEquals(new BigDecimal("1.20"), newsLi.cpm());
        assertEquals(500_000, newsLi.goalImpressions());             // $600 at $1.20 CPM
        assertEquals(List.of("BD"), newsLi.countries());
        assertEquals("draft", f.adServer.lineItemStatus.get(news.lineItemId())); // no creative yet

        LineItemSpec eduLi = f.adServer.lineItems.get(synced.packages().get(1).lineItemId());
        assertEquals(8, eduLi.priority());                           // guaranteed -> standard
        assertEquals(200_000, eduLi.goalImpressions());              // $700 at $3.50 CPM
    }

    @Test
    void repeatedSyncCreatesNothingTwice() {
        MediaBuy buy = f.book(KEY);
        f.reconciler.reconcile(buy.mediaBuyId());
        f.reconciler.reconcile(buy.mediaBuyId());
        f.reconciler.reconcile(buy.mediaBuyId());
        assertEquals(1, f.adServer.advertisers.size());
        assertEquals(1, f.adServer.orders.size());
        assertEquals(2, f.adServer.lineItems.size());
    }

    @Test
    void transientFailureIsRetriedThenSucceeds() {
        MediaBuy buy = f.book(KEY);
        f.adServer.failOn = "ensureLineItem";
        f.adServer.failure = new TraffickingException("ad server unreachable", false);

        MediaBuy failed = f.reconciler.reconcile(buy.mediaBuyId());
        assertEquals(Provisioning.PENDING, failed.provisioning());
        assertTrue(failed.dirty());
        assertEquals("ad server unreachable", failed.provisioningError());
        assertEquals(List.of(failed), f.buys.findDirty(10));

        MediaBuy ok = f.reconciler.reconcile(buy.mediaBuyId());
        assertEquals(Provisioning.PROVISIONED, ok.provisioning());
        assertFalse(ok.dirty());
        assertEquals(2, f.adServer.lineItems.size());
    }

    @Test
    void permanentFailureStopsRetrying() {
        MediaBuy buy = f.book(KEY);
        f.adServer.failOn = "ensureOrder";
        f.adServer.failure = new TraffickingException("ad server rejected POST /orders -> 400", true);
        MediaBuy failed = f.reconciler.reconcile(buy.mediaBuyId());
        assertEquals(Provisioning.FAILED, failed.provisioning());
        assertFalse(failed.dirty());
        assertTrue(failed.provisioningError().contains("400"));
    }

    @Test
    void pauseAndCancelFollowThroughToTheOrder() {
        MediaBuy buy = f.book(KEY);
        String order = f.reconciler.reconcile(buy.mediaBuyId()).traffickingOrderId();

        assertTrue(f.service.setPaused(Fixture.ACCOUNT, buy.mediaBuyId(), true).dirty());
        f.reconciler.reconcile(buy.mediaBuyId());
        assertEquals("paused", f.adServer.orderStatus.get(order));

        f.service.cancel(Fixture.ACCOUNT, buy.mediaBuyId());
        f.reconciler.reconcile(buy.mediaBuyId());
        assertEquals("archived", f.adServer.orderStatus.get(order));
    }

    @Test
    void creativesAreCreatedOnceAttachedAndActivateTheLineItem() {
        MediaBuy buy = f.reconciler.reconcile(f.book(KEY).mediaBuyId());
        String newsPkg = buy.packages().get(0).packageId();
        f.creativeService.sync(Fixture.ACCOUNT, List.of(
                new CreativeInput("acme_banner_1", "Banner", "display_300x250", "https://cdn.acme.test/b.png", null,
                        "https://acme.test/sale")), Map.of("acme_banner_1", List.of(newsPkg)));

        MediaBuy synced = f.reconciler.reconcile(buy.mediaBuyId());
        f.reconciler.reconcile(buy.mediaBuyId());
        String li = synced.packages().get(0).lineItemId();
        assertEquals(1, f.adServer.creatives.size());
        assertEquals(1, f.adServer.attached.get(li).size());
        assertEquals("active", f.adServer.lineItemStatus.get(li));
        assertEquals("draft", f.adServer.lineItemStatus.get(synced.packages().get(1).lineItemId()));
        var spec = f.adServer.creatives.values().iterator().next();
        assertEquals("image", spec.type());
        assertEquals(300, spec.width());
        assertEquals("https://acme.test/sale", spec.landingUrl());
        assertEquals("adcp:" + Fixture.ACCOUNT + ":acme_banner_1", spec.externalRef());
        assertTrue(f.creatives.find(Fixture.ACCOUNT, "acme_banner_1").orElseThrow().traffickingCreativeId() != null);
    }

    @Test
    void buyerChangeDuringSyncKeepsTheBuyDirty() {
        MediaBuy buy = f.book(KEY);
        f.adServer.duringEnsureOrder = () -> f.service.setPaused(Fixture.ACCOUNT, buy.mediaBuyId(), true);

        MediaBuy after = f.reconciler.reconcile(buy.mediaBuyId());
        assertEquals(MediaBuyStatus.PAUSED, after.status());   // the buyer's change is kept
        assertTrue(after.dirty());                             // and will be synced on the next run
        assertNotNull(after.packages().get(0).lineItemId());   // the sync's results are kept too

        MediaBuy next = f.reconciler.reconcile(buy.mediaBuyId());
        assertFalse(next.dirty());
        assertEquals("paused", f.adServer.orderStatus.get(next.traffickingOrderId()));
    }

    @Test
    void orderStatusMapping() {
        assertEquals("active", Reconciler.orderStatus(MediaBuyStatus.PENDING_ACTIVATION));
        assertEquals("active", Reconciler.orderStatus(MediaBuyStatus.ACTIVE));
        assertEquals("paused", Reconciler.orderStatus(MediaBuyStatus.PAUSED));
        assertEquals("archived", Reconciler.orderStatus(MediaBuyStatus.CANCELED));
        assertEquals("archived", Reconciler.orderStatus(MediaBuyStatus.COMPLETED));
    }
}

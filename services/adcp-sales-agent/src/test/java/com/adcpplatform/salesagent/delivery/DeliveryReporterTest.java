package com.adcpplatform.salesagent.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.adcpplatform.salesagent.Fixture;
import com.adcpplatform.salesagent.adserver.TraffickingException;
import com.adcpplatform.salesagent.mediabuy.AdcpException;
import com.adcpplatform.salesagent.model.Models.MediaBuy;
import com.adcpplatform.salesagent.model.Models.MediaBuyDelivery;

class DeliveryReporterTest {

    private final Fixture f = new Fixture();

    @Test
    void reportsRealImpressionsWithSpendCappedAtBudget() {
        MediaBuy buy = f.reconciler.reconcile(f.book("acme-key-0000000010").mediaBuyId());
        String newsLi = buy.packages().get(0).lineItemId();
        String eduLi = buy.packages().get(1).lineItemId();
        f.adServer.delivered.put(newsLi, 250_000L);   // $1.20 CPM -> $300.00
        f.adServer.delivered.put(eduLi, 300_000L);    // $3.50 CPM -> $1050, capped at the $700 budget
        f.clock.now = Fixture.NOW.plus(Duration.ofDays(15)); // half way through the flight

        MediaBuyDelivery d = new DeliveryReporter(f.adServer, f.clock).report(buy);
        assertEquals(550_000, d.totals().impressions());
        assertEquals(new BigDecimal("300.00"), d.byPackage().get(0).spend());
        assertEquals(new BigDecimal("700"), d.byPackage().get(1).spend());
        assertEquals(new BigDecimal("1000.00"), d.totals().spend());
        assertNull(d.totals().clicks());
        assertEquals(1.0, d.byPackage().get(0).pacingIndex(), 0.05); // 250k of an expected ~250k
    }

    @Test
    void unsyncedPackagesReportZeroAndOutagesAreExplicit() {
        MediaBuy buy = f.book("acme-key-0000000011"); // not yet in the ad server
        assertEquals(0, new DeliveryReporter(f.adServer, f.clock).report(buy).totals().impressions());

        MediaBuy synced = f.reconciler.reconcile(buy.mediaBuyId());
        f.adServer.failOn = "delivered";
        f.adServer.failure = new TraffickingException("down", false);
        AdcpException e = assertThrows(AdcpException.class, () -> new DeliveryReporter(f.adServer, f.clock).report(synced));
        assertEquals("DELIVERY_UNAVAILABLE", e.errors().getFirst().code());
    }
}

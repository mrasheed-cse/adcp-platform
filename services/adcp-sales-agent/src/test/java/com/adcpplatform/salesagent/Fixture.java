package com.adcpplatform.salesagent;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.adcpplatform.salesagent.adserver.FakeAdServer;
import com.adcpplatform.salesagent.adserver.Reconciler;
import com.adcpplatform.salesagent.catalog.ProductCatalog;
import com.adcpplatform.salesagent.creative.CreativeService;
import com.adcpplatform.salesagent.creative.InMemoryCreativeRepository;
import com.adcpplatform.salesagent.mediabuy.InMemoryMediaBuyRepository;
import com.adcpplatform.salesagent.mediabuy.MediaBuyService;
import com.adcpplatform.salesagent.mediabuy.MediaBuyServiceAccess;
import com.adcpplatform.salesagent.model.Models.MediaBuy;
import com.adcpplatform.salesagent.model.Models.PackageRequest;

/** Wires the sales agent's services over in-memory storage and a fake ad server. */
public class Fixture {
    public static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    public static final String ACCOUNT = "acct-acme";

    public static final class MutableClock extends Clock {
        public Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    public final MutableClock clock = new MutableClock();
    public final ProductCatalog catalog = new ProductCatalog();
    public final InMemoryMediaBuyRepository buys = new InMemoryMediaBuyRepository();
    public final InMemoryCreativeRepository creatives = new InMemoryCreativeRepository();
    public final MediaBuyService service = MediaBuyServiceAccess.create(catalog, buys, clock);
    public final FakeAdServer adServer = new FakeAdServer();
    public final Reconciler reconciler = new Reconciler(buys, service, creatives, catalog, adServer);
    public final CreativeService creativeService = new CreativeService(creatives, service, catalog);

    /** News display (non-guaranteed, $1.20 CPM) + education native (guaranteed, $3.50 CPM). */
    public MediaBuy book(String key) {
        return service.create(ACCOUNT, "acme.com", "asap", "2026-11-30T23:59:59Z", List.of(
                new PackageRequest("bd_news_display", "cpm_usd_auction", new BigDecimal("600"), "news"),
                new PackageRequest("bd_education_native", "cpm_usd_fixed", new BigDecimal("700"), "edu")), key).mediaBuy();
    }
}

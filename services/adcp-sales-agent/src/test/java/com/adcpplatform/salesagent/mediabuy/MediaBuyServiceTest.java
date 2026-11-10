package com.adcpplatform.salesagent.mediabuy;

import com.adcpplatform.salesagent.catalog.ProductCatalog;
import com.adcpplatform.salesagent.model.Models.MediaBuy;
import com.adcpplatform.salesagent.model.Models.MediaBuyStatus;
import com.adcpplatform.salesagent.model.Models.PackageRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaBuyServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final String KEY = "acme-cricket-2026-11-0001";
    private static final String ACME = "acct-acme";
    private static final String OTHER = "acct-other";

    /** Clock the test can move forward. */
    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryMediaBuyRepository repository = new InMemoryMediaBuyRepository();
    private final MediaBuyService service = new MediaBuyService(new ProductCatalog(), repository, clock);

    private static List<PackageRequest> cricketPackage(String budget) {
        return List.of(new PackageRequest("bd_sports_ctv", "cpm_usd_fixed", new BigDecimal(budget), null));
    }

    private MediaBuy createAsap(String account, String budget, String key) {
        return service.create(account, "acme.com", "asap", "2026-11-30T23:59:59Z", cricketPackage(budget), key)
                .mediaBuy();
    }

    // ---------------- basics ----------------

    @Test
    void createsActiveBuyWhenStartIsAsap() {
        var result = service.create(ACME, "acme.com", "asap", "2026-11-30T23:59:59Z", cricketPackage("5000"), KEY);

        MediaBuy buy = result.mediaBuy();
        assertFalse(result.replayed());
        assertEquals(MediaBuyStatus.ACTIVE, buy.status());
        assertEquals(new BigDecimal("5000"), buy.totalBudget());
        assertEquals("USD", buy.currency());
        assertEquals(1, buy.packages().size());
    }

    @Test
    void futureStartIsPendingActivation() {
        var buy = service.create(ACME, "acme.com", "2026-11-10T00:00:00Z", "2026-11-30T00:00:00Z",
                cricketPackage("5000"), KEY).mediaBuy();
        assertEquals(MediaBuyStatus.PENDING_ACTIVATION, buy.status());
    }

    @Test
    void rejectsShortIdempotencyKey() {
        var ex = assertThrows(AdcpException.class, () -> createAsap(ACME, "5000", "too-short"));
        assertEquals("idempotency_key", ex.errors().getFirst().field());
    }

    @Test
    void rejectsBudgetBelowMinimumSpend() {
        var ex = assertThrows(AdcpException.class, () -> createAsap(ACME, "100", KEY));
        assertEquals("BUDGET_TOO_LOW", ex.errors().getFirst().code());
    }

    @Test
    void rejectsUnknownProductAndPricingOption() {
        var packages = List.of(
                new PackageRequest("no_such_product", "cpm_usd_fixed", new BigDecimal("5000"), null),
                new PackageRequest("bd_sports_ctv", "cpc_usd", new BigDecimal("5000"), null));
        var ex = assertThrows(AdcpException.class, () ->
                service.create(ACME, "acme.com", "asap", "2026-11-30T23:59:59Z", packages, KEY));
        assertEquals(2, ex.errors().size());
        assertEquals("PRODUCT_NOT_FOUND", ex.errors().get(0).code());
        assertEquals("INVALID_PRICING_OPTION", ex.errors().get(1).code());
    }

    @Test
    void rejectsEndBeforeStart() {
        var ex = assertThrows(AdcpException.class, () ->
                service.create(ACME, "acme.com", "2026-11-20T00:00:00Z", "2026-11-10T00:00:00Z",
                        cricketPackage("5000"), KEY));
        assertEquals("end_time", ex.errors().getFirst().field());
    }

    // ---------------- fix 1: idempotency conflict ----------------

    @Test
    void identicalRetryReturnsOriginalBuy() {
        MediaBuy first = createAsap(ACME, "5000", KEY);
        var retry = service.create(ACME, "acme.com", "asap", "2026-11-30T23:59:59Z", cricketPackage("5000.00"), KEY);

        assertTrue(retry.replayed());
        assertEquals(first.mediaBuyId(), retry.mediaBuy().mediaBuyId());
        assertEquals(1, service.list(ACME, null).size());
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        createAsap(ACME, "5000", KEY);
        var ex = assertThrows(AdcpException.class, () -> createAsap(ACME, "9000", KEY));
        assertEquals("IDEMPOTENCY_CONFLICT", ex.errors().getFirst().code());
        assertEquals(1, service.list(ACME, null).size());
    }

    @Test
    void concurrentRetriesWithSameKeyCreateExactlyOneBuy() throws Exception {
        int threads = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var go = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return createAsap(ACME, "5000", KEY).mediaBuyId();
            }));
        }
        go.countDown();
        var ids = new java.util.HashSet<String>();
        for (var f : futures) ids.add(f.get());
        pool.shutdown();

        assertEquals(1, ids.size());
        assertEquals(1, service.list(ACME, null).size());
    }

    // ---------------- fix 2: account scoping ----------------

    @Test
    void sameKeyInDifferentAccountsCreatesSeparateBuys() {
        MediaBuy a = createAsap(ACME, "5000", KEY);
        MediaBuy b = createAsap(OTHER, "5000", KEY);
        assertFalse(a.mediaBuyId().equals(b.mediaBuyId()));
    }

    @Test
    void accountCannotSeeOrChangeAnotherAccountsBuy() {
        String id = createAsap(ACME, "5000", KEY).mediaBuyId();

        assertEquals("MEDIA_BUY_NOT_FOUND",
                assertThrows(AdcpException.class, () -> service.get(OTHER, id)).errors().getFirst().code());
        assertEquals("MEDIA_BUY_NOT_FOUND",
                assertThrows(AdcpException.class, () -> service.cancel(OTHER, id)).errors().getFirst().code());
        assertEquals(0, service.list(OTHER, null).size());
        assertEquals(MediaBuyStatus.ACTIVE, service.get(ACME, id).status());
    }

    // ---------------- fix 3: optimistic locking ----------------

    @Test
    void staleWriteIsRejectedByVersionCheck() {
        MediaBuy original = createAsap(ACME, "5000", KEY);
        service.setPaused(ACME, original.mediaBuyId(), true);           // version 0 -> 1

        MediaBuy staleCancel = original.withStatus(MediaBuyStatus.CANCELED, NOW);
        assertFalse(repository.updateIfVersion(staleCancel, original.version()));
        assertEquals(MediaBuyStatus.PAUSED, service.get(ACME, original.mediaBuyId()).status());
    }

    @Test
    void pauseResumeAndCancelFollowStateMachineAndBumpVersion() {
        String id = createAsap(ACME, "5000", KEY).mediaBuyId();

        assertEquals(MediaBuyStatus.PAUSED, service.setPaused(ACME, id, true).status());
        assertEquals(MediaBuyStatus.ACTIVE, service.setPaused(ACME, id, false).status());
        MediaBuy canceled = service.cancel(ACME, id);
        assertEquals(MediaBuyStatus.CANCELED, canceled.status());
        assertEquals(3L, canceled.version());

        var ex = assertThrows(AdcpException.class, () -> service.setPaused(ACME, id, false));
        assertEquals("INVALID_STATE", ex.errors().getFirst().code());
    }

    // ---------------- fix 4: reads are side-effect free; scheduler advances status ----------------

    @Test
    void readsDoNotChangeStatusButSchedulerDoes() {
        String id = service.create(ACME, "acme.com", "2026-11-02T00:00:00Z", "2026-11-03T00:00:00Z",
                cricketPackage("5000"), KEY).mediaBuy().mediaBuyId();

        clock.advance(Duration.ofDays(1).plusHours(1));                 // start has passed
        assertEquals(MediaBuyStatus.PENDING_ACTIVATION, service.get(ACME, id).status());
        assertEquals(0L, service.get(ACME, id).version());

        assertEquals(1, service.advanceTimeBasedStatuses());
        assertEquals(MediaBuyStatus.ACTIVE, service.get(ACME, id).status());

        clock.advance(Duration.ofDays(1));                               // end has passed
        assertEquals(1, service.advanceTimeBasedStatuses());
        assertEquals(MediaBuyStatus.COMPLETED, service.get(ACME, id).status());
        assertEquals(0, service.advanceTimeBasedStatuses());             // nothing left to do
    }

    @Test
    void cannotResumeAfterFlightEnded() {
        String id = service.create(ACME, "acme.com", "asap", "2026-11-02T00:00:00Z",
                cricketPackage("5000"), KEY).mediaBuy().mediaBuyId();
        service.setPaused(ACME, id, true);
        clock.advance(Duration.ofDays(2));

        var ex = assertThrows(AdcpException.class, () -> service.setPaused(ACME, id, false));
        assertEquals("INVALID_STATE", ex.errors().getFirst().code());
    }
}

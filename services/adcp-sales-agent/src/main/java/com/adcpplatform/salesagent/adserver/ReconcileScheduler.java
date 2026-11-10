package com.adcpplatform.salesagent.adserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.adcpplatform.salesagent.mediabuy.MediaBuyRepository;
import com.adcpplatform.salesagent.model.Models.MediaBuy;

/**
 * Retries media buys whose changes have not reached the ad server (network errors, ad server down,
 * status changes made by the time-based scheduler). With several instances, guard with ShedLock.
 */
@Component
public class ReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcileScheduler.class);
    private static final int BATCH = 50;

    private final MediaBuyRepository buys;
    private final Reconciler reconciler;

    public ReconcileScheduler(MediaBuyRepository buys, Reconciler reconciler) {
        this.buys = buys;
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${adcp.trafficking.reconcile-interval-ms:15000}")
    public void run() {
        for (MediaBuy b : buys.findDirty(BATCH)) {
            try {
                reconciler.reconcile(b.mediaBuyId());
            } catch (RuntimeException e) {
                log.error("reconcile of media buy {} crashed", b.mediaBuyId(), e);
            }
        }
    }
}

package com.adcpplatform.salesagent.mediabuy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves media buys through time-based states so that read tools never have to write.
 * With several app instances, guard this with ShedLock (or a DB advisory lock) so only one runs it.
 */
@Component
public class StatusScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatusScheduler.class);

    private final MediaBuyService service;

    public StatusScheduler(MediaBuyService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${adcp.status-refresh-interval-ms:30000}")
    public void refresh() {
        int changed = service.advanceTimeBasedStatuses();
        if (changed > 0) {
            log.info("advanced status of {} media buy(s)", changed);
        }
    }
}

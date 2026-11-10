package com.adcpplatform.salesagent.delivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.adcpplatform.salesagent.adserver.TraffickingClient;
import com.adcpplatform.salesagent.adserver.TraffickingException;
import com.adcpplatform.salesagent.mediabuy.AdcpException;
import com.adcpplatform.salesagent.model.Models.DeliveryTotals;
import com.adcpplatform.salesagent.model.Models.MediaBuy;
import com.adcpplatform.salesagent.model.Models.MediaBuyDelivery;
import com.adcpplatform.salesagent.model.Models.MediaBuyPackage;
import com.adcpplatform.salesagent.model.Models.PackageDelivery;

/**
 * Delivery from the ad server's real impression counts. Spend = impressions x CPM / 1000, capped at the
 * package budget. Clicks are reported as null until click reporting is read from ClickHouse.
 */
@Component
public class DeliveryReporter {

    private final TraffickingClient adServer;
    private final Clock clock;

    public DeliveryReporter(TraffickingClient adServer) {
        this(adServer, Clock.systemUTC());
    }

    DeliveryReporter(TraffickingClient adServer, Clock clock) {
        this.adServer = adServer;
        this.clock = clock;
    }

    public MediaBuyDelivery report(MediaBuy buy) {
        Instant now = clock.instant();
        Instant periodEnd = now.isBefore(buy.endTime()) ? now : buy.endTime();
        double elapsed = flightFraction(buy, now);

        List<PackageDelivery> rows = new ArrayList<>();
        long totalImps = 0;
        BigDecimal totalSpend = BigDecimal.ZERO;
        for (MediaBuyPackage p : buy.packages()) {
            long imps = 0;
            if (p.lineItemId() != null) {
                try {
                    imps = adServer.delivered(p.lineItemId());
                } catch (TraffickingException e) {
                    throw new AdcpException("DELIVERY_UNAVAILABLE", "delivery data is temporarily unavailable; retry shortly", null);
                }
            }
            BigDecimal spend = p.rate() == null ? BigDecimal.ZERO
                    : p.rate().multiply(BigDecimal.valueOf(imps)).divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP)
                            .min(p.budget());
            double expected = p.impressionGoal() * elapsed;
            double pacing = expected <= 0 ? 1.0 : round(imps / expected, 2);
            rows.add(new PackageDelivery(p.packageId(), imps, null, spend, pacing));
            totalImps += imps;
            totalSpend = totalSpend.add(spend);
        }
        return new MediaBuyDelivery(buy.mediaBuyId(), buy.status(), buy.startTime(), periodEnd, buy.currency(),
                new DeliveryTotals(totalImps, null, totalSpend, null), rows);
    }

    private static double flightFraction(MediaBuy buy, Instant now) {
        if (!now.isAfter(buy.startTime())) {
            return 0.0;
        }
        long total = Duration.between(buy.startTime(), buy.endTime()).toSeconds();
        long done = Duration.between(buy.startTime(), now).toSeconds();
        return total <= 0 ? 1.0 : Math.min(1.0, (double) done / total);
    }

    private static double round(double v, int places) {
        return BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }
}

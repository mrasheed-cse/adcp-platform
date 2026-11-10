package com.adcpplatform.salesagent.mediabuy;

import java.time.Clock;

import com.adcpplatform.salesagent.catalog.ProductCatalog;

/** Exposes the package-private clock constructor to tests in other packages. */
public final class MediaBuyServiceAccess {
    private MediaBuyServiceAccess() {}

    public static MediaBuyService create(ProductCatalog catalog, MediaBuyRepository repo, Clock clock) {
        return new MediaBuyService(catalog, repo, clock);
    }
}

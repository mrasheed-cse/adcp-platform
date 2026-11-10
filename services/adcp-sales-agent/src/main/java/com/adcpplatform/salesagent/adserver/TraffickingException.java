package com.adcpplatform.salesagent.adserver;

/**
 * A call to the ad server failed. Transient failures (network, 5xx, races) are retried by the reconciler;
 * permanent ones (the ad server rejected the data) are reported to the buyer and not retried.
 */
public class TraffickingException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean permanent;

    public TraffickingException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public TraffickingException(String message, boolean permanent) {
        this(message, permanent, null);
    }

    public boolean permanent() {
        return permanent;
    }
}

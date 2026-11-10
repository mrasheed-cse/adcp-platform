package com.adcpplatform.salesagent.adserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "adcp.trafficking")
public record TraffickingProperties(String baseUrl, long timeoutMs) {

    public TraffickingProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8083";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 3000;
        }
    }
}

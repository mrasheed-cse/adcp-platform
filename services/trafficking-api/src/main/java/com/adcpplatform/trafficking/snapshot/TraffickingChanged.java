package com.adcpplatform.trafficking.snapshot;

/** Published inside a transaction whenever something that can affect serving changes. */
public record TraffickingChanged(String reason) {}

package com.adcpplatform.trafficking.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The editable fields of a line item, as sent by the API and checked by LineItemRules. */
public record LineItemFields(
        String name,
        int priority,
        BigDecimal cpm,
        Instant start,
        Instant end,
        long goalImpressions,
        Integer freqMax,
        Integer freqWindowSeconds,
        List<String> countries,
        List<String> devices,
        List<String> placements) {

    public LineItemFields {
        countries = countries == null ? List.of() : List.copyOf(countries);
        devices = devices == null ? List.of() : List.copyOf(devices);
        placements = placements == null ? List.of() : List.copyOf(placements);
    }
}

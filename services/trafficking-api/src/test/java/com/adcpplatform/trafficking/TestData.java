package com.adcpplatform.trafficking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.adcpplatform.trafficking.domain.AdOrder;
import com.adcpplatform.trafficking.domain.Creative;
import com.adcpplatform.trafficking.domain.CreativeType;
import com.adcpplatform.trafficking.domain.LineItem;
import com.adcpplatform.trafficking.domain.LineItemFields;
import com.adcpplatform.trafficking.domain.LineItemStatus;

public final class TestData {
    public static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    private TestData() {}

    public static LineItemFields fields() {
        return new LineItemFields("Cricket sponsorship", 4, new BigDecimal("4.25"),
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-15T00:00:00Z"), 100_000,
                5, 86_400, List.of("bd"), List.of("mobile"), List.of("sports_top"));
    }

    public static Creative image(String id, String advertiserId) {
        return new Creative(id, advertiserId, "Banner", CreativeType.IMAGE, 300, 250,
                "https://cdn.example.com/a.png", null, "https://brand.example.com/");
    }

    /** An active, servable line item unless the caller changes it. */
    public static LineItem active(String id) {
        AdOrder order = new AdOrder("ord_" + id, "adv_1", "Order");
        LineItem li = new LineItem(id, order);
        li.apply(fields(), NOW);
        li.getCreatives().add(image("cr_" + id, "adv_1"));
        li.setStatus(LineItemStatus.ACTIVE, NOW);
        return li;
    }
}

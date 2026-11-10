package com.adcpplatform.trafficking.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.adcpplatform.trafficking.TestData;
import com.adcpplatform.trafficking.domain.CreativeType;
import com.adcpplatform.trafficking.domain.Creative;
import com.adcpplatform.trafficking.domain.LineItem;
import com.adcpplatform.trafficking.domain.LineItemFields;
import com.adcpplatform.trafficking.domain.LineItemStatus;
import com.adcpplatform.trafficking.domain.OrderStatus;

class SnapshotBuilderTest {

    @Test
    void includesOnlyServableLineItemsSortedById() {
        LineItem b = TestData.active("li_b");
        LineItem a = TestData.active("li_a");
        LineItem paused = TestData.active("li_paused");
        paused.setStatus(LineItemStatus.PAUSED, TestData.NOW);
        LineItem orderPaused = TestData.active("li_order_paused");
        orderPaused.getOrder().setStatus(OrderStatus.PAUSED);
        LineItem noCreatives = TestData.active("li_no_creatives");
        noCreatives.getCreatives().clear();
        LineItem ended = TestData.active("li_ended");

        SnapshotJson s = SnapshotBuilder.build(List.of(b, paused, orderPaused, noCreatives, a, ended), 7,
                Instant.parse("2026-11-05T00:00:00Z"));
        assertEquals(7, s.version());
        assertEquals(List.of("li_a", "li_b", "li_ended"), s.lineItems().stream().map(SnapshotJson.LineItemJson::id).toList());

        SnapshotJson later = SnapshotBuilder.build(List.of(ended), 8, Instant.parse("2026-11-15T00:00:00Z"));
        assertEquals(0, later.lineItems().size()); // end is exclusive
    }

    @Test
    void mapsFieldsToTheContract() {
        LineItem li = TestData.active("li_x");
        li.getCreatives().add(new Creative("cr_html", "adv_1", "House", CreativeType.HTML, 300, 250, null, "<b>x</b>",
                "https://pub.test/"));
        SnapshotJson.LineItemJson j = SnapshotBuilder.build(List.of(li), 1, TestData.NOW).lineItems().getFirst();

        assertEquals("ord_li_x", j.orderId());
        assertEquals("adv_1", j.advertiserId());
        assertEquals(4, j.priority());
        assertEquals(List.of("BD"), j.targeting().countries()); // upper-cased on apply
        assertEquals(List.of("mobile"), j.targeting().devices());
        assertEquals(100_000, j.goal().impressions());
        assertEquals(5, j.frequencyCap().max());
        assertEquals(86_400, j.frequencyCap().windowSeconds());
        assertEquals(List.of("cr_html", "cr_li_x"), j.creatives().stream().map(SnapshotJson.CreativeJson::id).toList());
        assertEquals("html", j.creatives().get(0).type());
        assertNull(j.creatives().get(0).imageUrl());
        assertEquals("image", j.creatives().get(1).type());
    }

    @Test
    void noFrequencyCapIsOmitted() {
        LineItem li = TestData.active("li_nofc");
        LineItemFields f = TestData.fields();
        li.apply(new LineItemFields(f.name(), f.priority(), f.cpm(), f.start(), f.end(), f.goalImpressions(),
                null, null, f.countries(), f.devices(), f.placements()), TestData.NOW);
        assertNull(SnapshotBuilder.build(List.of(li), 1, TestData.NOW).lineItems().getFirst().frequencyCap());
    }
}

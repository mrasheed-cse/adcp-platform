package com.adcpplatform.salesagent.adserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory ad server that honours external references like the real trafficking API. */
public class FakeAdServer implements TraffickingClient {

    public final Map<String, String> advertisers = new LinkedHashMap<>();   // ref -> id
    public final Map<String, String> orders = new LinkedHashMap<>();        // ref -> id
    public final Map<String, String> orderStatus = new HashMap<>();         // id -> status
    public final Map<String, LineItemSpec> lineItems = new LinkedHashMap<>(); // id -> spec
    public final Map<String, String> lineItemByRef = new HashMap<>();
    public final Map<String, String> lineItemStatus = new HashMap<>();
    public final Map<String, CreativeSpec> creatives = new LinkedHashMap<>(); // id -> spec
    public final Map<String, String> creativeByRef = new HashMap<>();
    public final Map<String, Set<String>> attached = new HashMap<>();
    public final Set<String> placements = new LinkedHashSet<>();
    public final Map<String, Long> delivered = new HashMap<>();
    public final List<String> calls = new ArrayList<>();

    /** When set, the named method throws this once, then works again. */
    public String failOn;
    public TraffickingException failure;
    /** Runs during ensureOrder, to simulate a buyer change racing with a sync. */
    public Runnable duringEnsureOrder;

    private int seq;

    private void maybeFail(String method) {
        calls.add(method);
        if (method.equals(failOn)) {
            failOn = null;
            throw failure;
        }
    }

    @Override
    public String ensureAdvertiser(String name, String externalRef) {
        maybeFail("ensureAdvertiser");
        return advertisers.computeIfAbsent(externalRef, r -> "adv_" + (++seq));
    }

    @Override
    public void ensurePlacement(String placementId, String name) {
        maybeFail("ensurePlacement");
        placements.add(placementId);
    }

    @Override
    public String ensureOrder(String advertiserId, String name, String externalRef) {
        maybeFail("ensureOrder");
        if (duringEnsureOrder != null) {
            Runnable r = duringEnsureOrder;
            duringEnsureOrder = null;
            r.run();
        }
        String id = orders.computeIfAbsent(externalRef, r -> "ord_" + (++seq));
        orderStatus.putIfAbsent(id, "active");
        return id;
    }

    @Override
    public void setOrderStatus(String orderId, String status) {
        maybeFail("setOrderStatus");
        orderStatus.put(orderId, status);
    }

    @Override
    public String ensureLineItem(LineItemSpec spec) {
        maybeFail("ensureLineItem");
        String id = lineItemByRef.computeIfAbsent(spec.externalRef(), r -> "li_" + (++seq));
        lineItems.putIfAbsent(id, spec);
        lineItemStatus.putIfAbsent(id, "draft");
        return id;
    }

    @Override
    public void setLineItemStatus(String lineItemId, String status) {
        maybeFail("setLineItemStatus");
        lineItemStatus.put(lineItemId, status);
    }

    @Override
    public String ensureCreative(CreativeSpec spec) {
        maybeFail("ensureCreative");
        String id = creativeByRef.computeIfAbsent(spec.externalRef(), r -> "cr_" + (++seq));
        creatives.putIfAbsent(id, spec);
        return id;
    }

    @Override
    public void attachCreative(String lineItemId, String creativeId) {
        maybeFail("attachCreative");
        attached.computeIfAbsent(lineItemId, k -> new LinkedHashSet<>()).add(creativeId);
    }

    @Override
    public long delivered(String lineItemId) {
        maybeFail("delivered");
        return delivered.getOrDefault(lineItemId, 0L);
    }
}

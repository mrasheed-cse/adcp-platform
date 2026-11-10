package com.adcpplatform.salesagent.creative;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.adcpplatform.salesagent.model.Models.SyncedCreative;

/** For tests; not a Spring bean. */
public class InMemoryCreativeRepository implements CreativeRepository {

    private final ConcurrentMap<String, SyncedCreative> byKey = new ConcurrentHashMap<>();

    private static String key(String accountId, String creativeId) {
        return accountId + "|" + creativeId;
    }

    @Override
    public Optional<SyncedCreative> find(String accountId, String creativeId) {
        return Optional.ofNullable(byKey.get(key(accountId, creativeId)));
    }

    @Override
    public boolean insertIfAbsent(SyncedCreative c) {
        return byKey.putIfAbsent(key(c.accountId(), c.creativeId()), c) == null;
    }

    @Override
    public void setTraffickingId(String accountId, String creativeId, String id) {
        byKey.computeIfPresent(key(accountId, creativeId), (k, c) -> new SyncedCreative(c.accountId(), c.creativeId(),
                c.name(), c.formatId(), c.imageUrl(), c.html(), c.clickUrl(), id, c.createdAt()));
    }
}

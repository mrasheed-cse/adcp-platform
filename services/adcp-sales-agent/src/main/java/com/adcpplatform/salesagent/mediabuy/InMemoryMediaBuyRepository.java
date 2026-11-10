package com.adcpplatform.salesagent.mediabuy;

import com.adcpplatform.salesagent.model.Models.MediaBuy;
import com.adcpplatform.salesagent.model.Models.MediaBuyStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory implementation for tests and local experiments; not a Spring bean. */
public class InMemoryMediaBuyRepository implements MediaBuyRepository {

    private final ConcurrentMap<String, MediaBuy> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idByKey = new ConcurrentHashMap<>();

    private static String scopedKey(String accountId, String idempotencyKey) {
        return accountId + "|" + idempotencyKey;
    }

    @Override
    public Optional<MediaBuy> findById(String mediaBuyId) {
        return Optional.ofNullable(byId.get(mediaBuyId));
    }

    @Override
    public Optional<MediaBuy> findByIdempotencyKey(String accountId, String idempotencyKey) {
        return Optional.ofNullable(idByKey.get(scopedKey(accountId, idempotencyKey))).map(byId::get);
    }

    @Override
    public MediaBuy saveIfKeyAbsent(MediaBuy buy) {
        // Store the buy first so a winner's id is always resolvable by readers.
        byId.put(buy.mediaBuyId(), buy);
        String winner = idByKey.putIfAbsent(scopedKey(buy.accountId(), buy.idempotencyKey()), buy.mediaBuyId());
        if (winner != null) {
            byId.remove(buy.mediaBuyId());  // we lost the race: drop our copy
            return byId.get(winner);
        }
        return buy;
    }

    @Override
    public boolean updateIfVersion(MediaBuy updated, long expectedVersion) {
        boolean[] applied = {false};
        byId.computeIfPresent(updated.mediaBuyId(), (id, current) -> {
            if (current.version() != expectedVersion) {
                return current;             // stale write: keep what is there
            }
            applied[0] = true;
            return updated;
        });
        return applied[0];
    }

    @Override
    public Collection<MediaBuy> findByAccount(String accountId) {
        return byId.values().stream().filter(b -> b.accountId().equals(accountId)).toList();
    }

    @Override
    public Collection<MediaBuy> findOpen() {
        return byId.values().stream()
                .filter(b -> b.status() == MediaBuyStatus.PENDING_ACTIVATION || b.status() == MediaBuyStatus.ACTIVE
                        || b.status() == MediaBuyStatus.PAUSED)
                .toList();
    }

    @Override
    public List<MediaBuy> findDirty(int limit) {
        return byId.values().stream().filter(MediaBuy::dirty)
                .sorted(java.util.Comparator.comparing(MediaBuy::updatedAt)).limit(limit).toList();
    }
}

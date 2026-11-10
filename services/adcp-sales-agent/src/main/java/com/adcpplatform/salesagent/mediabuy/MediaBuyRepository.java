package com.adcpplatform.salesagent.mediabuy;

import com.adcpplatform.salesagent.model.Models.MediaBuy;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Storage port. Production: JdbcMediaBuyRepository (Postgres). Tests: InMemoryMediaBuyRepository.
 *  - saveIfKeyAbsent  -> INSERT ... ON CONFLICT (account_id, idempotency_key) DO NOTHING
 *  - updateIfVersion  -> UPDATE ... WHERE id = ? AND version = ?
 */
public interface MediaBuyRepository {

    Optional<MediaBuy> findById(String mediaBuyId);

    Optional<MediaBuy> findByIdempotencyKey(String accountId, String idempotencyKey);

    /**
     * Atomically store the buy unless (accountId, idempotencyKey) already exists.
     * Returns the existing buy if the key was already used, otherwise the newly saved one.
     */
    MediaBuy saveIfKeyAbsent(MediaBuy buy);

    /**
     * Optimistic update: replaces the stored buy only if its version still equals expectedVersion.
     * Returns false if someone else updated it first.
     */
    boolean updateIfVersion(MediaBuy updated, long expectedVersion);

    Collection<MediaBuy> findByAccount(String accountId);

    /** Buys that are not finished yet (pending activation, active, paused). */
    Collection<MediaBuy> findOpen();

    /** Buys with changes the ad server has not caught up with, oldest first. */
    List<MediaBuy> findDirty(int limit);
}

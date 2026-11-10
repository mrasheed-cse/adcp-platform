package com.adcpplatform.salesagent.creative;

import java.util.Optional;

import com.adcpplatform.salesagent.model.Models.SyncedCreative;

public interface CreativeRepository {

    Optional<SyncedCreative> find(String accountId, String creativeId);

    /** Stores the creative if (account, creative id) is new; returns true when inserted. */
    boolean insertIfAbsent(SyncedCreative creative);

    void setTraffickingId(String accountId, String creativeId, String traffickingCreativeId);
}

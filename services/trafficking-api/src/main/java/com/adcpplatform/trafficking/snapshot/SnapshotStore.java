package com.adcpplatform.trafficking.snapshot;

import java.util.Map;

/** Where snapshots are published and delivery counts are read. Implemented on Redis. */
public interface SnapshotStore {

    /** Next strictly increasing snapshot version. */
    long nextVersion();

    /** Stores the snapshot unless a newer version is already there. Returns true if stored. */
    boolean publish(String snapshotJson, long version);

    /** Impressions delivered so far, per line item id. */
    Map<String, Long> delivered();
}

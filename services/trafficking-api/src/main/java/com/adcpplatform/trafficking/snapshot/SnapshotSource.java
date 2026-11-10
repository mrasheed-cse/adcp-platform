package com.adcpplatform.trafficking.snapshot;

import java.time.Instant;

public interface SnapshotSource {
    SnapshotJson build(long version, Instant now);
}

package com.adcpplatform.trafficking.snapshot;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.adcpplatform.trafficking.domain.LineItemStatus;
import com.adcpplatform.trafficking.service.LineItemRepository;

/** Reads active line items with their order and creatives in one query and builds the snapshot. */
@Component
public class ServableSnapshotSource implements SnapshotSource {

    private final LineItemRepository lineItems;

    public ServableSnapshotSource(LineItemRepository lineItems) {
        this.lineItems = lineItems;
    }

    @Override
    @Transactional(readOnly = true)
    public SnapshotJson build(long version, Instant now) {
        return SnapshotBuilder.build(lineItems.findWithOrderAndCreativesByStatus(LineItemStatus.ACTIVE), version, now);
    }
}

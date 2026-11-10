package com.adcpplatform.trafficking.snapshot;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Publishes the servable snapshot to Redis after every committed change, on startup, and on a timer.
 * Requests are coalesced on one background thread: a burst of changes produces at most one extra publish.
 */
@Component
public class SnapshotPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SnapshotPublisher.class);

    private final SnapshotSource source;
    private final SnapshotStore store;
    private final Function<SnapshotJson, String> serializer;
    private final Clock clock;
    private final ExecutorService executor;
    private final AtomicBoolean pending = new AtomicBoolean();

    public SnapshotPublisher(SnapshotSource source, SnapshotStore store, ObjectMapper mapper, Clock clock) {
        this(source, store, s -> toJson(mapper, s), clock,
                Executors.newSingleThreadExecutor(r -> new Thread(r, "snapshot-publisher")));
    }

    SnapshotPublisher(SnapshotSource source, SnapshotStore store, Function<SnapshotJson, String> serializer,
                      Clock clock, ExecutorService executor) {
        this.source = source;
        this.store = store;
        this.serializer = serializer;
        this.clock = clock;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChange(TraffickingChanged event) {
        requestPublish();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        requestPublish();
    }

    @Scheduled(fixedDelayString = "${trafficking.snapshot.republish-interval-ms:60000}")
    public void periodic() {
        requestPublish();
    }

    /** Queues a publish unless one is already queued. Never blocks the caller. */
    public void requestPublish() {
        if (pending.compareAndSet(false, true)) {
            executor.execute(() -> {
                pending.set(false); // later requests queue a fresh run that sees their changes
                publishNow();
            });
        }
    }

    /** Builds and publishes one snapshot. Version is taken first so a slower, older build can never win. */
    public boolean publishNow() {
        try {
            long version = store.nextVersion();
            SnapshotJson snapshot = source.build(version, clock.instant());
            boolean stored = store.publish(serializer.apply(snapshot), version);
            if (stored) {
                log.info("published snapshot version={} line_items={}", version, snapshot.lineItems().size());
            } else {
                log.info("skipped snapshot version={}: a newer one is already published", version);
            }
            return stored;
        } catch (RuntimeException e) {
            log.error("snapshot publish failed; the next change or timer will retry", e);
            return false;
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    private static String toJson(ObjectMapper mapper, SnapshotJson s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize snapshot", e);
        }
    }
}

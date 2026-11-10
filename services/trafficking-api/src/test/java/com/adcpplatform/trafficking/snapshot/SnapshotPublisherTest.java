package com.adcpplatform.trafficking.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class SnapshotPublisherTest {

    /** Runs queued tasks only when told to, so coalescing can be observed. */
    static final class ManualExecutor extends AbstractExecutorService {
        final Deque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable r) { queue.add(r); }
        void runAll() { while (!queue.isEmpty()) queue.poll().run(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    static class FakeStore implements SnapshotStore {
        long seq;
        long stored;
        final List<Long> published = new ArrayList<>();
        @Override public long nextVersion() { return ++seq; }
        @Override public boolean publish(String json, long version) {
            if (version <= stored) return false;
            stored = version;
            published.add(version);
            return true;
        }
        @Override public Map<String, Long> delivered() { return Map.of(); }
    }

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);
    private final List<Long> builtVersions = new ArrayList<>();
    private final SnapshotSource source = (v, now) -> {
        builtVersions.add(v);
        return new SnapshotJson(v, now, List.of());
    };

    @Test
    void burstOfChangesIsCoalesced() {
        FakeStore store = new FakeStore();
        ManualExecutor ex = new ManualExecutor();
        SnapshotPublisher p = new SnapshotPublisher(source, store, s -> "{}", clock, ex);

        for (int i = 0; i < 5; i++) p.requestPublish();
        assertEquals(1, ex.queue.size());
        ex.runAll();
        assertEquals(List.of(1L), store.published);

        p.requestPublish(); // a change after the run queues a fresh publish
        ex.runAll();
        assertEquals(List.of(1L, 2L), store.published);
    }

    @Test
    void versionIsTakenBeforeBuilding() {
        FakeStore store = new FakeStore();
        SnapshotPublisher p = new SnapshotPublisher(source, store, s -> "{}", clock, new ManualExecutor());
        assertTrue(p.publishNow());
        assertEquals(List.of(1L), builtVersions);
    }

    @Test
    void olderVersionIsNotPublished() {
        FakeStore store = new FakeStore();
        store.stored = 10; // another instance already published v10
        SnapshotPublisher p = new SnapshotPublisher(source, store, s -> "{}", clock, new ManualExecutor());
        assertFalse(p.publishNow());
        assertEquals(List.of(), store.published);
    }

    @Test
    void failuresAreContained() {
        SnapshotStore broken = new FakeStore() {
            @Override public long nextVersion() { throw new IllegalStateException("redis down"); }
        };
        SnapshotPublisher p = new SnapshotPublisher(source, broken, s -> "{}", clock, new ManualExecutor());
        assertFalse(p.publishNow());
    }
}

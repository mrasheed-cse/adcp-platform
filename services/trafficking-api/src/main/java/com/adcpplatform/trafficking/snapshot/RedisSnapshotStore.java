package com.adcpplatform.trafficking.snapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis keys and semantics match pkg/lineitem in the Go code and contracts/line-item-snapshot.schema.json. */
@Component
public class RedisSnapshotStore implements SnapshotStore {

    static final String SNAPSHOT_KEY = "adserver:snapshot";
    static final String VERSION_KEY = "adserver:snapshot:version";
    static final String SEQUENCE_KEY = "adserver:snapshot:seq";
    static final String DELIVERED_KEY = "adserver:delivered";

    /** Writes snapshot and version together, and never replaces a newer snapshot with an older one. */
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[2]) or '0')
            if tonumber(ARGV[2]) <= current then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('SET', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisSnapshotStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long nextVersion() {
        Long v = redis.opsForValue().increment(SEQUENCE_KEY);
        if (v == null) {
            throw new IllegalStateException("Redis INCR returned no value");
        }
        return v;
    }

    @Override
    public boolean publish(String snapshotJson, long version) {
        Long stored = redis.execute(PUBLISH, List.of(SNAPSHOT_KEY, VERSION_KEY), snapshotJson, Long.toString(version));
        return stored != null && stored == 1L;
    }

    @Override
    public Map<String, Long> delivered() {
        Map<Object, Object> raw = redis.opsForHash().entries(DELIVERED_KEY);
        Map<String, Long> out = new HashMap<>();
        raw.forEach((k, v) -> {
            try {
                out.put(k.toString(), Long.parseLong(v.toString()));
            } catch (NumberFormatException ignored) {
                // skip malformed counters
            }
        });
        return out;
    }
}

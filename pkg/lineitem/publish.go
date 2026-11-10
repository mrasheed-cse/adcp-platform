package lineitem

import "github.com/redis/go-redis/v9"

// PublishScript writes the snapshot (KEYS[1]) and its version (KEYS[2]) together, and only when
// ARGV[2] is newer than the stored version, so an older publish can never replace a newer one.
// The Java trafficking API runs the same script (RedisSnapshotStore.PUBLISH).
var PublishScript = redis.NewScript(`
local current = tonumber(redis.call('GET', KEYS[2]) or '0')
if tonumber(ARGV[2]) <= current then
  return 0
end
redis.call('SET', KEYS[1], ARGV[1])
redis.call('SET', KEYS[2], ARGV[2])
return 1
`)

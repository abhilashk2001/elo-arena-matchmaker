package com.eloarena.matchmaking;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Live operational counters kept in Redis so the dashboard can read them cheaply without
 * touching Postgres. Postgres remains the source of truth; these are derived, best-effort
 * stats updated by the matcher each tick.
 */
@Service
public class RedisLiveStats {

    static final String MATCHES_CREATED_KEY = "eloarena:stats:matches_created";
    static final String QUEUE_DEPTH_KEY = "eloarena:stats:queue_depth";

    private final StringRedisTemplate redis;

    public RedisLiveStats(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void recordMatchesCreated(long count) {
        if (count > 0) {
            redis.opsForValue().increment(MATCHES_CREATED_KEY, count);
        }
    }

    public void setQueueDepth(long depth) {
        redis.opsForValue().set(QUEUE_DEPTH_KEY, Long.toString(depth));
    }

    public long matchesCreated() {
        return readLong(MATCHES_CREATED_KEY);
    }

    public long queueDepth() {
        return readLong(QUEUE_DEPTH_KEY);
    }

    private long readLong(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }
}

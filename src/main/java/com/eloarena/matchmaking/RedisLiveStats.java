package com.eloarena.matchmaking;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Live operational counters kept in Redis so the dashboard can read them cheaply without
 * touching Postgres. Postgres remains the source of truth; these are derived, best-effort
 * stats updated by the matcher each tick.
 */
@Service
public class RedisLiveStats {

    static final String MATCHES_CREATED_KEY = "eloarena:stats:matches_created";
    static final String QUEUE_DEPTH_KEY = "eloarena:stats:queue_depth";

    // matches/sec is a sliding window of per-second buckets. Each tick increments the bucket for
    // the current second; the rate is the sum of the last WINDOW_SECONDS whole buckets averaged
    // out. Buckets expire on their own, so the window self-cleans and we never store unbounded
    // history. A short window keeps the number responsive when the strategy is toggled live.
    static final String MATCHES_BUCKET_PREFIX = "eloarena:stats:matches_sec:";
    static final int WINDOW_SECONDS = 5;

    private final StringRedisTemplate redis;

    public RedisLiveStats(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void recordMatchesCreated(long count) {
        if (count > 0) {
            redis.opsForValue().increment(MATCHES_CREATED_KEY, count);
            long second = Instant.now().getEpochSecond();
            String bucket = MATCHES_BUCKET_PREFIX + second;
            redis.opsForValue().increment(bucket, count);
            // Outlive the read window by a couple of seconds so a bucket is never expired out
            // from under a read that still counts it. After that it is dead weight, so let it go.
            redis.expire(bucket, Duration.ofSeconds(WINDOW_SECONDS + 2));
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

    /**
     * Matches created per second, averaged over the last {@link #WINDOW_SECONDS} whole seconds.
     * The current (partial) second is skipped so the rate is not understated by a second that is
     * still filling. One MGET, so the read costs a single Redis round trip regardless of window.
     */
    public double matchesPerSec() {
        long now = Instant.now().getEpochSecond();
        List<String> keys = new ArrayList<>(WINDOW_SECONDS);
        for (int i = 1; i <= WINDOW_SECONDS; i++) {
            keys.add(MATCHES_BUCKET_PREFIX + (now - i));
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        long sum = 0;
        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    sum += Long.parseLong(value);
                }
            }
        }
        return (double) sum / WINDOW_SECONDS;
    }

    private long readLong(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }
}

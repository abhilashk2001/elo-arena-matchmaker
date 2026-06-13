package com.eloarena.leaderboard;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * The live leaderboard, a Redis sorted set per season (member = player id, score = rating).
 * Postgres is the source of truth; this is a read-optimised projection updated after a result
 * commits. Phase 6 adds top-N and rank reads, the rebuild-from-Postgres op, and season reset.
 */
@Service
public class Leaderboard {

    private final StringRedisTemplate redis;

    public Leaderboard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void addRating(long seasonId, long playerId, int rating) {
        redis.opsForZSet().add(key(seasonId), Long.toString(playerId), rating);
    }

    /** Current score (rating) of a player on the leaderboard, or null if absent. */
    public Double scoreOf(long seasonId, long playerId) {
        return redis.opsForZSet().score(key(seasonId), Long.toString(playerId));
    }

    public static String key(long seasonId) {
        return "leaderboard:season:" + seasonId;
    }
}

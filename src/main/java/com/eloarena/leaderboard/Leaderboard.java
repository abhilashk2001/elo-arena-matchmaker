package com.eloarena.leaderboard;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The live leaderboard, a Redis sorted set per season (member = player id, score = rating).
 * Postgres is the source of truth; this is a read-optimised projection updated after a result
 * commits. Top-N is ZREVRANGE (O(log N + M)) and a player's rank is ZREVRANK (O(log N)).
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

    /** Top {@code limit} players by rating, highest first, with 1-based ranks. */
    public List<Entry> topN(long seasonId, int limit) {
        Set<TypedTuple<String>> tuples = redis.opsForZSet()
                .reverseRangeWithScores(key(seasonId), 0, limit - 1L);
        List<Entry> entries = new ArrayList<>();
        long rank = 1;
        if (tuples != null) {
            for (TypedTuple<String> tuple : tuples) {
                entries.add(new Entry(Long.parseLong(tuple.getValue()), tuple.getScore().intValue(), rank++));
            }
        }
        return entries;
    }

    /** A player's 1-based rank, or null if they are not on the leaderboard. */
    public Long rankOf(long seasonId, long playerId) {
        Long reverseRank = redis.opsForZSet().reverseRank(key(seasonId), Long.toString(playerId));
        return reverseRank == null ? null : reverseRank + 1;
    }

    public static String key(long seasonId) {
        return "leaderboard:season:" + seasonId;
    }

    /** One ranked leaderboard row (before handle hydration). */
    public record Entry(long playerId, int rating, long rank) {
    }
}

package com.eloarena.leaderboard;

import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import com.eloarena.season.SeasonService;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Rebuilds the live leaderboard for the active season from Postgres, the source of truth.
 *
 * This is the recovery path for the post-commit window (a result committed but its ZADD never
 * ran, or Redis was flushed entirely): one scan of players, pipelined ZADDs, and the leaderboard
 * matches the truth again. It is why treating Redis as a rebuildable cache is safe.
 */
@Service
public class LeaderboardRebuildService {

    private final PlayerRepository players;
    private final StringRedisTemplate redis;
    private final SeasonService seasons;

    public LeaderboardRebuildService(PlayerRepository players, StringRedisTemplate redis, SeasonService seasons) {
        this.players = players;
        this.redis = redis;
        this.seasons = seasons;
    }

    public long rebuildActiveSeason() {
        long seasonId = seasons.currentSeasonId();
        String key = Leaderboard.key(seasonId);
        List<Player> all = players.findAll();

        redis.delete(key);
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object execute(RedisOperations operations) {
                for (Player player : all) {
                    operations.opsForZSet().add(key, Long.toString(player.getId()), player.getRating());
                }
                return null;
            }
        });
        return all.size();
    }
}

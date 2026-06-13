package com.eloarena.leaderboard;

import com.eloarena.api.LeaderboardEntryResponse;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import com.eloarena.season.Season;
import com.eloarena.season.SeasonLeaderboardSnapshot;
import com.eloarena.season.SeasonLeaderboardSnapshotRepository;
import com.eloarena.season.SeasonRepository;
import com.eloarena.season.SeasonService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assembles leaderboard responses. Active seasons read the live Redis sorted set; ended seasons
 * read the stored snapshot. Both paths hydrate player handles with a single IN query and return
 * the same response shape, so callers do not care which source was used.
 */
@Service
public class LeaderboardService {

    private final Leaderboard leaderboard;
    private final PlayerRepository players;
    private final SeasonRepository seasons;
    private final SeasonLeaderboardSnapshotRepository snapshots;
    private final SeasonService seasonService;

    public LeaderboardService(Leaderboard leaderboard,
                              PlayerRepository players,
                              SeasonRepository seasons,
                              SeasonLeaderboardSnapshotRepository snapshots,
                              SeasonService seasonService) {
        this.leaderboard = leaderboard;
        this.players = players;
        this.seasons = seasons;
        this.snapshots = snapshots;
        this.seasonService = seasonService;
    }

    /** Top-N of the active season, from the live leaderboard. */
    public List<LeaderboardEntryResponse> activeTopN(int limit) {
        return liveEntries(seasonService.currentSeasonId(), limit);
    }

    /** Top-N for any season: live if active, snapshot if ended. */
    public List<LeaderboardEntryResponse> seasonTopN(long seasonId, int limit) {
        Season season = seasons.findById(seasonId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown season " + seasonId));
        if (season.getEndedAt() == null) {
            return liveEntries(seasonId, limit);
        }
        List<SeasonLeaderboardSnapshot> rows =
                snapshots.findBySeasonIdOrderByFinalRankAsc(seasonId, PageRequest.of(0, limit));
        Map<Long, String> handles = handlesFor(rows.stream().map(SeasonLeaderboardSnapshot::getPlayerId).toList());
        return rows.stream()
                .map(s -> new LeaderboardEntryResponse(
                        s.getFinalRank(), s.getPlayerId(), handles.get(s.getPlayerId()), s.getFinalRating()))
                .toList();
    }

    private List<LeaderboardEntryResponse> liveEntries(long seasonId, int limit) {
        List<Leaderboard.Entry> entries = leaderboard.topN(seasonId, limit);
        Map<Long, String> handles = handlesFor(entries.stream().map(Leaderboard.Entry::playerId).toList());
        return entries.stream()
                .map(e -> new LeaderboardEntryResponse(e.rank(), e.playerId(), handles.get(e.playerId()), e.rating()))
                .toList();
    }

    private Map<Long, String> handlesFor(List<Long> playerIds) {
        return players.findAllById(playerIds).stream()
                .collect(Collectors.toMap(Player::getId, Player::getHandle, (a, b) -> a));
    }
}

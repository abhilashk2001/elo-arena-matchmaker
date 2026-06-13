package com.eloarena.api;

import com.eloarena.leaderboard.Leaderboard;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerNotFoundException;
import com.eloarena.player.PlayerRepository;
import com.eloarena.rating.RatingHistoryRepository;
import com.eloarena.season.SeasonService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final PlayerRepository players;
    private final RatingHistoryRepository history;
    private final Leaderboard leaderboard;
    private final SeasonService seasons;

    public PlayerController(PlayerRepository players,
                            RatingHistoryRepository history,
                            Leaderboard leaderboard,
                            SeasonService seasons) {
        this.players = players;
        this.history = history;
        this.leaderboard = leaderboard;
        this.seasons = seasons;
    }

    @GetMapping("/{id}")
    public PlayerProfileResponse profile(@PathVariable long id) {
        Player player = players.findById(id).orElseThrow(() -> new PlayerNotFoundException(id));
        Long rank = leaderboard.rankOf(seasons.currentSeasonId(), id);
        return new PlayerProfileResponse(
                player.getId(), player.getHandle(), player.getRating(),
                player.getRegion(), player.getGamesPlayed(), rank);
    }

    @GetMapping("/{id}/history")
    public List<PlayerHistoryEntry> history(@PathVariable long id,
                                            @RequestParam(defaultValue = "20") int limit) {
        if (!players.existsById(id)) {
            throw new PlayerNotFoundException(id);
        }
        return history.findByPlayerIdOrderByCreatedAtDesc(id, PageRequest.of(0, limit)).stream()
                .map(h -> new PlayerHistoryEntry(h.getMatchId(), h.getRatingBefore(), h.getRatingAfter(), h.getCreatedAt()))
                .toList();
    }
}

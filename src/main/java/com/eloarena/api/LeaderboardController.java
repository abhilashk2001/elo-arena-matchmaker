package com.eloarena.api;

import com.eloarena.leaderboard.LeaderboardService;
import com.eloarena.season.SeasonRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class LeaderboardController {

    private final LeaderboardService leaderboard;
    private final SeasonRepository seasons;

    public LeaderboardController(LeaderboardService leaderboard, SeasonRepository seasons) {
        this.leaderboard = leaderboard;
        this.seasons = seasons;
    }

    @GetMapping("/leaderboard")
    public List<LeaderboardEntryResponse> activeLeaderboard(@RequestParam(defaultValue = "20") int limit) {
        return leaderboard.activeTopN(limit);
    }

    @GetMapping("/seasons")
    public List<SeasonResponse> seasons() {
        return seasons.findAllByOrderByIdAsc().stream()
                .map(s -> new SeasonResponse(s.getId(), s.getName(), s.getStartedAt(), s.getEndedAt(), s.getEndedAt() == null))
                .toList();
    }

    @GetMapping("/seasons/{id}/leaderboard")
    public List<LeaderboardEntryResponse> seasonLeaderboard(@PathVariable long id,
                                                            @RequestParam(defaultValue = "20") int limit) {
        return leaderboard.seasonTopN(id, limit);
    }
}

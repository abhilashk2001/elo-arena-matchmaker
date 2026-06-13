package com.eloarena.api;

import com.eloarena.match.MatchNotFoundException;
import com.eloarena.match.MatchRepository;
import com.eloarena.match.ResultService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/matches")
public class MatchController {

    private final ResultService resultService;
    private final MatchRepository matches;

    public MatchController(ResultService resultService, MatchRepository matches) {
        this.resultService = resultService;
        this.matches = matches;
    }

    @GetMapping("/{id}")
    public MatchDetailResponse get(@PathVariable long id) {
        return matches.findById(id)
                .map(MatchDetailResponse::from)
                .orElseThrow(() -> new MatchNotFoundException(id));
    }

    @PostMapping("/{id}/result")
    public MatchResultResponse submitResult(@PathVariable long id, @Valid @RequestBody SubmitResultRequest request) {
        return resultService.submit(id, request.winnerId());
    }
}

package com.eloarena.api;

import com.eloarena.match.ResultService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/matches")
public class MatchController {

    private final ResultService resultService;

    public MatchController(ResultService resultService) {
        this.resultService = resultService;
    }

    @PostMapping("/{id}/result")
    public MatchResultResponse submitResult(@PathVariable long id, @Valid @RequestBody SubmitResultRequest request) {
        return resultService.submit(id, request.winnerId());
    }
}

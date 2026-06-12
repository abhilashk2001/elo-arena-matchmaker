package com.eloarena.api;

import com.eloarena.matchmaking.QueueEntry;
import com.eloarena.matchmaking.QueueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/join")
    @ResponseStatus(HttpStatus.CREATED)
    public QueueEntryResponse join(@Valid @RequestBody JoinQueueRequest request) {
        QueueEntry entry = queueService.join(request.playerId());
        return QueueEntryResponse.from(entry);
    }

    @DeleteMapping("/{playerId}")
    @ResponseStatus(HttpStatus.OK)
    public void leave(@PathVariable long playerId) {
        queueService.leave(playerId);
    }
}

package com.eloarena.matchmaking;

import com.eloarena.player.Player;
import com.eloarena.player.PlayerNotFoundException;
import com.eloarena.player.PlayerRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queue operations: joining and leaving.
 */
@Service
public class QueueService {

    private final PlayerRepository players;
    private final QueueEntryRepository queue;

    public QueueService(PlayerRepository players, QueueEntryRepository queue) {
        this.players = players;
        this.queue = queue;
    }

    /**
     * Add a player to the queue, snapshotting their current rating.
     *
     * Rather than checking "is this player already waiting?" and then inserting (a
     * check-then-act race the matcher could slip through), we attempt the insert and let
     * the uq_player_waiting partial unique index decide. A violation means the player is
     * already waiting, which we surface as 409. saveAndFlush forces the insert now so the
     * violation is raised here and not deferred to commit.
     */
    @Transactional
    public QueueEntry join(long playerId) {
        Player player = players.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException(playerId));

        QueueEntry entry = new QueueEntry(playerId, player.getRating());
        try {
            return queue.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            throw new AlreadyQueuedException(playerId);
        }
    }
}

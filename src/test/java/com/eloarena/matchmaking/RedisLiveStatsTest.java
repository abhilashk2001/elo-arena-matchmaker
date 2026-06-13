package com.eloarena.matchmaking;

import com.eloarena.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisLiveStatsTest extends IntegrationTest {

    @Autowired
    private RedisLiveStats liveStats;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        redis.delete(List.of(RedisLiveStats.MATCHES_CREATED_KEY, RedisLiveStats.QUEUE_DEPTH_KEY));
    }

    @Test
    void matchesCreatedAccumulates() {
        liveStats.recordMatchesCreated(3);
        liveStats.recordMatchesCreated(2);
        assertThat(liveStats.matchesCreated()).isEqualTo(5);
    }

    @Test
    void queueDepthIsTheLatestValue() {
        liveStats.setQueueDepth(42);
        liveStats.setQueueDepth(17);
        assertThat(liveStats.queueDepth()).isEqualTo(17);
    }
}

package com.setminusx.ramsey.qm.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisQueueServiceTest {

    /**
     * Every per-stage key the system creates must be deleted when the stage is retired.
     *
     * processed_count was created on every stage and deleted nowhere, so it leaked exactly one key
     * per stage forever — 332,555 of them (of 713,374 total keys, 653 MB) had accumulated on the
     * live instance across campaign 3's ~330k stages. Nothing reads a retired stage's count, so the
     * only symptom is unbounded growth toward Dragonfly's 7 GB cap and progressively slower scans.
     */
    @Test
    void clearingAStageDeletesEveryPerStageKeyIncludingTheProcessedCount() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // clearStageCounter touches only the template; the mapper is irrelevant here.
        RedisQueueService svc = new RedisQueueService(redis, null);

        svc.clearStageCounter(4242);

        verify(redis).delete("stage_work_index:4242");
        verify(redis).delete("stage_config:4242");
        verify(redis).delete("processed_count:4242");
    }
}

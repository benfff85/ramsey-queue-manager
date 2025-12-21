package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.model.BestResult;
import com.setminusx.ramsey.qm.model.WorkQueueItem;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing the Redis work queue.
 */
@Slf4j
@Service
public class RedisQueueService {

    private static final String QUEUE_KEY_PREFIX = "work_queue:";
    private static final String BEST_RESULT_KEY_PREFIX = "best_result:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisQueueService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Push multiple work items to the queue using batch operation.
     */
    public void pushWorkItems(Integer stageId, List<WorkQueueItem> items) {
        String queueKey = getQueueKey(stageId);

        // Serialize all items first
        String[] jsonItems = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            try {
                jsonItems[i] = objectMapper.writeValueAsString(items.get(i));
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize work queue item", e);
            }
        }

        // Push all at once (much faster than individual pushes)
        redisTemplate.opsForList().leftPushAll(queueKey, jsonItems);
        log.debug("Pushed {} items to queue {}", items.size(), queueKey);
    }

    /**
     * Get the queue depth (O(1) operation!).
     */
    public Long getQueueDepth(Integer stageId) {
        Long size = redisTemplate.opsForList().size(getQueueKey(stageId));
        return size != null ? size : 0L;
    }

    /**
     * Clear the queue for a stage.
     */
    public void clearQueue(Integer stageId) {
        String queueKey = getQueueKey(stageId);
        redisTemplate.delete(queueKey);
        log.info("Cleared queue for stage {}", stageId);
    }

    /**
     * Get the best result for a stage (if any).
     */
    public Optional<BestResult> getBestResult(Integer stageId) {
        String key = getBestResultKey(stageId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            BestResult result = objectMapper.readValue(json, BestResult.class);
            return Optional.of(result);
        } catch (Exception e) {
            log.error("Failed to deserialize best result: {}", json, e);
            return Optional.empty();
        }
    }

    /**
     * Delete the best result for a stage.
     */
    public void deleteBestResult(Integer stageId) {
        String key = getBestResultKey(stageId);
        redisTemplate.delete(key);
        log.info("Deleted best result for stage {}", stageId);
    }

    private String getQueueKey(Integer stageId) {
        return QUEUE_KEY_PREFIX + stageId;
    }

    private String getBestResultKey(Integer stageId) {
        return BEST_RESULT_KEY_PREFIX + stageId;
    }

}

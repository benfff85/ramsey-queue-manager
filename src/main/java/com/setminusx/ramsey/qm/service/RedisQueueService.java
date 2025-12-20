package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.model.WorkQueueItem;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for managing the Redis work queue.
 */
@Slf4j
@Service
public class RedisQueueService {

    private static final String QUEUE_KEY_PREFIX = "work_queue:";

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

    private String getQueueKey(Integer stageId) {
        return QUEUE_KEY_PREFIX + stageId;
    }

}

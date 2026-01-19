package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.model.BestResult;
import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.Graph;
import com.setminusx.ramsey.qm.model.WorkQueueItem;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing the Redis work queue.
 */
@Slf4j
@Service
public class RedisQueueService {

    private static final String QUEUE_KEY_PREFIX = "work_queue:";
    private static final String BEST_RESULT_KEY_PREFIX = "best_result:";
    private static final String STAGE_WORK_INDEX_PREFIX = "stage_work_index:";
    private static final String STAGE_CONFIG_PREFIX = "stage_config:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisQueueService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ========== Counter-Based Work Distribution Methods ==========

    /**
     * Initialize counter-based work distribution for a stage.
     * Sets the work index to 0 and stores the stage configuration (including
     * graph).
     */
    public void initializeStageCounter(Integer stageId, Integer baseGraphId, Graph graph,
            long totalPairs, String enumerationStrategy) {
        String indexKey = STAGE_WORK_INDEX_PREFIX + stageId;
        String configKey = STAGE_CONFIG_PREFIX + stageId;

        // Initialize counter to 0
        redisTemplate.opsForValue().set(indexKey, "0");

        // Build stage config JSON with embedded graph
        try {
            Map<String, Object> config = new HashMap<>();
            config.put("stageId", stageId);
            config.put("baseGraphId", baseGraphId);
            config.put("strategy", enumerationStrategy);
            config.put("totalPairs", totalPairs);

            Map<String, Object> graphSnapshot = new HashMap<>();
            graphSnapshot.put("vertexCount", graph.getVertexCount());
            graphSnapshot.put("edgeData", graph.getEdgeData());
            config.put("graph", graphSnapshot);

            String configJson = objectMapper.writeValueAsString(config);
            redisTemplate.opsForValue().set(configKey, configJson);

            log.info("Initialized counter-based work for stage {}: totalPairs={}, strategy={}",
                    stageId, totalPairs, enumerationStrategy);
        } catch (Exception e) {
            log.error("Failed to serialize stage config for stage {}", stageId, e);
            throw new RuntimeException("Failed to initialize stage counter", e);
        }
    }

    /**
     * Get the current work index for a stage (how many work units have been
     * claimed).
     */
    public long getStageWorkIndex(Integer stageId) {
        String indexKey = STAGE_WORK_INDEX_PREFIX + stageId;
        String value = redisTemplate.opsForValue().get(indexKey);
        return value != null ? Long.parseLong(value) : 0L;
    }

    /**
     * Check if all work has been claimed for a counter-based stage.
     */
    public boolean isStageWorkFullyClaimed(Integer stageId, long totalPairs) {
        return getStageWorkIndex(stageId) >= totalPairs;
    }

    /**
     * Check if stage config exists (indicates counter-based mode was initialized).
     */
    public boolean hasStageConfig(Integer stageId) {
        String configKey = STAGE_CONFIG_PREFIX + stageId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(configKey));
    }

    /**
     * Clear counter-based keys for a stage (on stage progression).
     */
    public void clearStageCounter(Integer stageId) {
        String indexKey = STAGE_WORK_INDEX_PREFIX + stageId;
        String configKey = STAGE_CONFIG_PREFIX + stageId;
        redisTemplate.delete(indexKey);
        redisTemplate.delete(configKey);
        log.info("Cleared counter-based keys for stage {}", stageId);
    }

    // ========== Queue-Based Work Distribution Methods (existing) ==========

    /**
     * Push multiple work items to the queue using batch operation.
     * Uses compact format: baseGraphId|v1,v2|v1,v2
     */
    public void pushWorkItems(Integer stageId, List<WorkQueueItem> items) {
        String queueKey = getQueueKey(stageId);

        // Serialize to compact format: baseGraphId|v1,v2|v1,v2
        String[] compactItems = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            WorkQueueItem item = items.get(i);
            StringBuilder sb = new StringBuilder(30);
            sb.append(item.getBaseGraphId());
            for (Edge edge : item.getEdgesToFlip()) {
                sb.append('|').append(edge.getVertexOne()).append(',').append(edge.getVertexTwo());
            }
            compactItems[i] = sb.toString();
        }

        // Push all at once (much faster than individual pushes)
        redisTemplate.opsForList().leftPushAll(queueKey, compactItems);
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

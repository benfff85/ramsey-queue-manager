package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.model.BestResult;
import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.Graph;
import com.setminusx.ramsey.qm.model.WorkQueueItem;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service for managing the Redis work queue.
 */
@Slf4j
@Service
public class RedisQueueService {

    private static final String QUEUE_KEY_PREFIX = "work_queue:";
    private static final String BEST_RESULT_KEY_PREFIX = "best_result:";
    private static final String BEST_RESULTS_KEY_PREFIX = "best_results:"; // Sorted set for top-N
    private static final String STAGE_WORK_INDEX_PREFIX = "stage_work_index:";
    private static final String STAGE_CONFIG_PREFIX = "stage_config:";
    private static final String PROCESSED_COUNT_PREFIX = "processed_count:";
    private static final String PROCESSED_GRAPH_HASHES_KEY = "processed_graph_hashes";

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
     * Number of work units actually processed (evaluated and results submitted) for a stage.
     * Workers increment this only after submitting their batch results, so when it reaches
     * totalPairs every result is already published — no in-flight stragglers remain.
     */
    public long getProcessedCount(Integer stageId) {
        String value = redisTemplate.opsForValue().get(PROCESSED_COUNT_PREFIX + stageId);
        return value != null ? Long.parseLong(value) : 0L;
    }

    /**
     * Total work units for a stage from its Redis config, or -1 if unknown/uninitialized.
     */
    public long getStageTotalPairs(Integer stageId) {
        String configJson = redisTemplate.opsForValue().get(STAGE_CONFIG_PREFIX + stageId);
        if (configJson == null) {
            return -1L;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = objectMapper.readValue(configJson, Map.class);
            Object totalPairsObj = config.get("totalPairs");
            return totalPairsObj == null ? -1L : ((Number) totalPairsObj).longValue();
        } catch (Exception e) {
            log.error("Failed to parse stage config for totalPairs: {}", stageId, e);
            return -1L;
        }
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
    /**
     * Channel workers watch to learn a stage has advanced.
     *
     * They otherwise find out by polling the fleet endpoint once per work cycle, which at current
     * stage rates leaves them a large fraction of a stage behind: work finished against a
     * superseded stage is written to keys this class has already cleared, and workers drift onto
     * different graphs so they cannot pool the per-edge fill. One channel carries every campaign
     * and workers filter, so a fleet being repointed needs no resubscribe.
     */
    public static final String STAGE_ADVANCED_CHANNEL = "stage_advanced";

    /**
     * Announce that a campaign's active stage changed. Fire-and-forget: Redis pub/sub has no
     * delivery guarantee and workers keep polling the fleet endpoint regardless, so a dropped
     * message costs latency, never correctness.
     */
    public void publishStageAdvanced(Integer campaignId, Integer stageId) {
        try {
            redisTemplate.convertAndSend(STAGE_ADVANCED_CHANNEL,
                    String.format("{\"campaignId\":%d,\"stageId\":%d}", campaignId, stageId));
        } catch (Exception e) {
            log.warn("Could not announce stage {} for campaign {}: {}", stageId, campaignId, e.toString());
        }
    }

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

    private String getBestResultsKey(Integer stageId) {
        return BEST_RESULTS_KEY_PREFIX + stageId;
    }

    // ========== Top-N Best Results (Sorted Set) ==========

    /**
     * Get top N best results for a stage from the sorted set.
     * Results are ordered by clique count ascending (best first).
     */
    public List<BestResult> getTopResults(Integer stageId, int maxResults) {
        String key = getBestResultsKey(stageId);
        Set<String> results = redisTemplate.opsForZSet().range(key, 0, maxResults - 1);

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        List<BestResult> bestResults = new ArrayList<>();
        for (String json : results) {
            try {
                BestResult result = objectMapper.readValue(json, BestResult.class);
                bestResults.add(result);
            } catch (Exception e) {
                log.error("Failed to deserialize best result from sorted set: {}", json, e);
            }
        }
        return bestResults;
    }

    /**
     * Delete the top-N results sorted set for a stage.
     */
    public void deleteTopResults(Integer stageId) {
        String key = getBestResultsKey(stageId);
        redisTemplate.delete(key);
        log.info("Deleted top results sorted set for stage {}", stageId);
    }

    // ========== Stage Exhaustion Detection ==========

    /**
     * Check if a stage is exhausted (all work has been claimed).
     * Returns true if stage_work_index >= totalPairs from stage config.
     */
    public boolean isStageExhausted(Integer stageId) {
        long totalPairs = getStageTotalPairs(stageId);
        return totalPairs > 0 && getStageWorkIndex(stageId) >= totalPairs;
    }

    /**
     * Get totalPairs from stage config.
     */
    public Optional<Long> getTotalPairs(Integer stageId) {
        String configKey = STAGE_CONFIG_PREFIX + stageId;
        String configJson = redisTemplate.opsForValue().get(configKey);

        if (configJson == null) {
            return Optional.empty();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = objectMapper.readValue(configJson, Map.class);
            Object totalPairsObj = config.get("totalPairs");
            if (totalPairsObj == null) {
                return Optional.empty();
            }
            return Optional.of(((Number) totalPairsObj).longValue());
        } catch (Exception e) {
            log.error("Failed to parse stage config for totalPairs: {}", stageId, e);
            return Optional.empty();
        }
    }

    // ========== Processed Graph Hash Tracking ==========

    /**
     * Add a graph hash to the set of processed graphs.
     * Used to prevent revisiting graphs that have already been used as base graphs.
     */
    public void addProcessedGraphHash(String graphHash) {
        redisTemplate.opsForSet().add(PROCESSED_GRAPH_HASHES_KEY, graphHash);
        log.info("Added processed graph hash: {}", graphHash);
    }

    /**
     * Check if a graph hash has already been processed.
     */
    public boolean isGraphAlreadyProcessed(String graphHash) {
        Boolean isMember = redisTemplate.opsForSet().isMember(PROCESSED_GRAPH_HASHES_KEY, graphHash);
        return Boolean.TRUE.equals(isMember);
    }

    /**
     * Get all processed graph hashes (for debugging/monitoring).
     */
    public Set<String> getAllProcessedGraphHashes() {
        Set<String> members = redisTemplate.opsForSet().members(PROCESSED_GRAPH_HASHES_KEY);
        return members != null ? members : Set.of();
    }

    /**
     * Check if the processed graph hashes set is empty (e.g., after Redis data loss).
     */
    public boolean isProcessedGraphHashesEmpty() {
        Long size = redisTemplate.opsForSet().size(PROCESSED_GRAPH_HASHES_KEY);
        return size == null || size == 0;
    }

}

package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.BestResult;
import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.Graph;
import com.setminusx.ramsey.qm.model.Stage;
import com.setminusx.ramsey.qm.service.RedisQueueService;
import com.setminusx.ramsey.qm.utility.GraphHashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Monitors for improved results and triggers stage progression.
 * Handles two scenarios:
 * 1. Improvement found: best result has fewer cliques than base graph
 * 2. Stage exhausted: all work claimed, picks best unprocessed result
 */
@Slf4j
@Component
public class StageProgressionMonitor {

    private final MiddlewareClient middlewareClient;
    private final RedisQueueService redisQueueService;
    private final RamseyConfig ramseyConfig;

    // Track when each stage was first detected as exhausted
    private final Map<Integer, Instant> exhaustionDetectedAt = new ConcurrentHashMap<>();

    public StageProgressionMonitor(
            MiddlewareClient middlewareClient,
            RedisQueueService redisQueueService,
            RamseyConfig ramseyConfig) {
        this.middlewareClient = middlewareClient;
        this.redisQueueService = redisQueueService;
        this.ramseyConfig = ramseyConfig;
    }

    @Scheduled(fixedRateString = "${ramsey.stage-progression.frequency-in-millis:30000}")
    public void checkForProgression() {
        log.debug("Checking for stage progression");

        // Single campaign-agnostic QM: progress EVERY campaign's active stage.
        // Per-stage state (Redis keys, exhaustion tracking) is stage-scoped, so
        // this is just a loop over the existing per-stage logic.
        for (Stage currentStage : middlewareClient.getActiveStages()) {
            checkStageForProgression(currentStage);
        }
    }

    private void checkStageForProgression(Stage currentStage) {
        Integer stageId = currentStage.getStageId();

        // Get current base graph
        Graph baseGraph = middlewareClient.getGraphById(currentStage.getBaseGraphId());
        if (baseGraph == null || baseGraph.getCliqueCount() == null) {
            log.warn("Could not fetch base graph or clique count for stage {}", stageId);
            return;
        }

        // SCENARIO 1: improvement found (best result beats base). Only acted on when immediate
        // progression is enabled; when disabled the stage runs to exhaustion (SCENARIO 2) and the
        // best result across the whole work space is taken at the end.
        if (ramseyConfig.getStage().isImmediatelyProgressOnImprovement()
                && tryProgressOnImprovement(currentStage, baseGraph, stageId)) {
            return;
        }

        // SCENARIO 2: Check for exhaustion
        if (redisQueueService.isStageExhausted(stageId)) {
            handleExhaustedStage(currentStage, baseGraph, stageId);
        } else {
            // Not exhausted yet, clear any stale exhaustion tracking
            exhaustionDetectedAt.remove(stageId);
        }
    }

    /**
     * SCENARIO 1: if the best result beats the base graph and hasn't already been processed,
     * advance the stage to it. Returns true if the stage was progressed.
     */
    private boolean tryProgressOnImprovement(Stage currentStage, Graph baseGraph, Integer stageId) {
        List<BestResult> topResults = redisQueueService.getTopResults(stageId, 1);
        if (topResults.isEmpty()) {
            return false;
        }
        BestResult best = topResults.getFirst();
        if (best.getCliqueCount() >= baseGraph.getCliqueCount()) {
            return false;
        }

        // Guard against oscillation: don't progress to a graph we've already processed
        // (this prevents reverting when exhaustion handling picks a worse graph).
        String graphHash = best.isSimulatedAnnealingResult()
                ? GraphHashUtil.computeHash(best.getGraphBitstring())
                : GraphHashUtil.computeDerivedGraphHash(baseGraph, best.getEdgesToFlip());

        if (redisQueueService.isGraphAlreadyProcessed(graphHash)) {
            log.info("Improvement found (cliques={}) but graph already processed, treating as exhaustion case",
                    best.getCliqueCount());
            return false; // fall through to exhaustion handling
        }

        log.info("IMPROVEMENT FOUND! Stage {} base graph has {} cliques, best result has {} (source: {})",
                stageId, baseGraph.getCliqueCount(), best.getCliqueCount(),
                best.isSimulatedAnnealingResult() ? "SA" : "EXHAUSTIVE");
        exhaustionDetectedAt.remove(stageId); // Clear any exhaustion tracking
        progressStageWithHash(currentStage, baseGraph, best, graphHash);
        return true;
    }

    /**
     * Handle an exhausted stage - wait for delay, then pick best unprocessed
     * result.
     */
    private void handleExhaustedStage(Stage currentStage, Graph baseGraph, Integer stageId) {
        long totalPairs = redisQueueService.getStageTotalPairs(stageId);
        long processed = redisQueueService.getProcessedCount(stageId);

        if (isFullyProcessed(processed, totalPairs)) {
            // All claimed work units have been processed AND their results published (workers
            // increment processed_count only after submitting), so there are no in-flight
            // stragglers to wait for. Skip the exhaustion delay and advance immediately.
            exhaustionDetectedAt.remove(stageId);
            log.info("Stage {} fully processed ({}/{}) — advancing without exhaustion delay", stageId, processed, totalPairs);
        } else {
            // Work is fully claimed but not fully processed — likely a restarted/in-flight worker
            // whose claimed units may never report. We cannot wait on processed_count forever, so
            // wait out the exhaustion delay, then fall back to the best published result.
            Long delayMs = ramseyConfig.getStage().getExhaustionDelayMs();
            Instant detectedAt = exhaustionDetectedAt.computeIfAbsent(stageId, k -> {
                log.info("Stage {} claimed-exhausted but only {}/{} processed; starting {}ms straggler delay...",
                        stageId, processed, totalPairs, delayMs);
                return Instant.now();
            });
            long elapsedMs = Instant.now().toEpochMilli() - detectedAt.toEpochMilli();
            if (elapsedMs < delayMs) {
                log.debug("Stage {} straggler delay: {}ms / {}ms elapsed ({}/{} processed)",
                        stageId, elapsedMs, delayMs, processed, totalPairs);
                return;
            }
            log.info("Stage {} straggler delay complete ({}/{} processed), selecting best unprocessed result...",
                    stageId, processed, totalPairs);
        }

        // Get top N results
        int topCount = ramseyConfig.getStage().getTopResultsCount();
        List<BestResult> topResults = redisQueueService.getTopResults(stageId, topCount);

        if (topResults.isEmpty()) {
            log.warn("Stage {} exhausted but no results in sorted set! Cannot progress.", stageId);
            exhaustionDetectedAt.remove(stageId);
            return;
        }

        // Find the first unprocessed result
        for (BestResult result : topResults) {
            String graphHash = result.isSimulatedAnnealingResult()
                    ? GraphHashUtil.computeHash(result.getGraphBitstring())
                    : GraphHashUtil.computeDerivedGraphHash(baseGraph, result.getEdgesToFlip());

            if (!redisQueueService.isGraphAlreadyProcessed(graphHash)) {
                log.info("Progressing exhausted stage {} to best unprocessed result: cliques={}",
                        stageId, result.getCliqueCount());
                exhaustionDetectedAt.remove(stageId);
                progressStageWithHash(currentStage, baseGraph, result, graphHash);
                return;
            } else {
                log.debug("Result with cliques={} already processed, skipping", result.getCliqueCount());
            }
        }

        // All top results have been processed
        log.warn("Stage {} exhausted and ALL {} top results already processed! System is stuck.",
                stageId, topResults.size());
        exhaustionDetectedAt.remove(stageId);
        // TODO: Could mark campaign as STUCK or send alert
    }

    private void progressStageWithHash(Stage currentStage, Graph baseGraph, BestResult bestResult, String graphHash) {
        // 1. Get or construct the derived graph
        Graph derivedGraph;
        String source;

        if (bestResult.isSimulatedAnnealingResult()) {
            log.info("Progressing via SIMULATED_ANNEALING result from base graph {}", bestResult.getBaseGraphId());
            source = "SIMULATED_ANNEALING";
            derivedGraph = new Graph();
            derivedGraph.setEdgeData(bestResult.getGraphBitstring());
            derivedGraph.setVertexCount(baseGraph.getVertexCount());
            derivedGraph.setSubgraphSize(baseGraph.getSubgraphSize());
        } else {
            String edgesToFlipStr = formatEdgesForUrl(bestResult.getEdgesToFlip());
            log.info("Progressing via EXHAUSTIVE result from base {} with edges {}",
                    bestResult.getBaseGraphId(), edgesToFlipStr);
            source = "EXHAUSTIVE";
            derivedGraph = middlewareClient.getDerivedGraph(
                    bestResult.getBaseGraphId(), edgesToFlipStr);

            if (derivedGraph == null) {
                log.error("Failed to get derived graph");
                return;
            }
        }

        // 2. Set the clique count on the derived graph
        derivedGraph.setCliqueCount(bestResult.getCliqueCount());

        // 3. Record the graph hash as processed (before creating to avoid race)
        redisQueueService.addProcessedGraphHash(graphHash);

        // 4. Save the derived graph to DB
        log.info("Saving derived graph with clique count {}", bestResult.getCliqueCount());
        Graph savedGraph = middlewareClient.createGraph(derivedGraph);
        log.info("Created new graph with ID: {}", savedGraph.getGraphId());

        // 5. Mark current stage as INACTIVE
        log.info("Marking stage {} as INACTIVE", currentStage.getStageId());
        currentStage.setStatus(Stage.Status.INACTIVE);
        middlewareClient.updateStage(currentStage);

        // 6. Create new active stage with default enumeration strategy
        Stage newStage = new Stage();
        newStage.setStatus(Stage.Status.ACTIVE);
        newStage.setBaseGraphId(savedGraph.getGraphId());
        newStage.setCampaignId(currentStage.getCampaignId());
        newStage.setDetails("source: " + source);

        // Apply default work enumeration strategy if configured
        String defaultStrategy = ramseyConfig.getStage().getDefaultWorkEnumerationStrategy();
        if (defaultStrategy != null && !defaultStrategy.isBlank()) {
            newStage.setWorkEnumerationStrategy(defaultStrategy);
            log.info("Setting work enumeration strategy to: {}", defaultStrategy);
        }

        Stage createdStage = middlewareClient.createStage(newStage);
        log.info("Created new stage {} with base graph {} (source: {})",
                createdStage.getStageId(), savedGraph.getGraphId(), source);

        // 7. Initialize counter-based mode for new stage (before clearing old stage)
        if (createdStage.getWorkEnumerationStrategy() != null) {
            initializeRedisForStage(createdStage, savedGraph);
        }

        // 8. Clear Redis queue and counter keys for old stage
        log.info("Clearing Redis keys for old stage {}", currentStage.getStageId());
        redisQueueService.clearQueue(currentStage.getStageId());
        redisQueueService.clearStageCounter(currentStage.getStageId());
        redisQueueService.deleteBestResult(currentStage.getStageId());
        redisQueueService.deleteTopResults(currentStage.getStageId());

        log.info("Stage progression complete! New stage {} is now active", createdStage.getStageId());
    }

    /**
     * Total work units for a stage. Pair strategies use redCount * blueCount.
     * DUAL_EDGE_CARDINALITY_WITH_SINGLES prepends one work unit per edge of
     * EITHER color (singles = redCount + blueCount) — this MUST match the
     * Rust worker's DualCardinalityWithSinglesEnumerator; the worker refuses
     * the stage if the totals disagree.
     */
    static long computeTotalWorkUnits(long redCount, long blueCount, String strategy) {
        long pairs = redCount * blueCount;
        if ("DUAL_EDGE_CARDINALITY_WITH_SINGLES".equals(strategy)) {
            return redCount + blueCount + pairs;
        }
        return pairs;
    }

    /**
     * True when every work unit of an exhausted (fully-claimed) stage has also been processed
     * and published, so the stage can advance immediately without waiting out the exhaustion
     * delay. Requires a known positive total; processedCount may slightly exceed totalPairs on
     * the final partial batch, hence the >= comparison.
     */
    static boolean isFullyProcessed(long processedCount, long totalPairs) {
        return totalPairs > 0 && processedCount >= totalPairs;
    }

    /**
     * Format edges for URL parameter: {{v1:v2},{v3:v4}}
     */
    private String formatEdgesForUrl(List<Edge> edges) {
        return "{" + edges.stream()
                .map(e -> "{" + e.getVertexOne() + ":" + e.getVertexTwo() + "}")
                .collect(Collectors.joining(",")) + "}";
    }

    /**
     * Periodically check that the active stage is properly initialized in Redis.
     * This safeguards against Redis data loss (restarts/flushes).
     */
    @Scheduled(fixedRateString = "${ramsey.work-unit.queue.frequency-in-millis:5000}")
    public void ensureActiveStageInitialized() {
        // Single QM: ensure the Redis stage_config exists for EVERY campaign's
        // active stage (safeguards against Redis data loss for any of them).
        List<Stage> stages = middlewareClient.getActiveStages();

        if (stages.isEmpty()) {
            return;
        }

        for (Stage stage : stages) {
            if (stage.getWorkEnumerationStrategy() != null) {
                checkAndInitializeStage(stage);
            }
        }

        // Re-seed processed graph hashes (global add-only set) if it was lost —
        // reseed from the history of every live campaign.
        if (redisQueueService.isProcessedGraphHashesEmpty()) {
            stages.stream()
                    .map(Stage::getCampaignId)
                    .distinct()
                    .forEach(this::reseedProcessedGraphHashes);
        }
    }

    private void checkAndInitializeStage(Stage stage) {
        // Check if already initialized
        if (redisQueueService.hasStageConfig(stage.getStageId())) {
            // Already initialized, do nothing
            return;
        }

        // Need to initialize - fetch graph and compute total pairs
        log.info("Active stage {} config missing in Redis. Re-initializing...", stage.getStageId());

        Graph graph = middlewareClient.getGraphById(stage.getBaseGraphId());
        if (graph == null) {
            log.error("Could not fetch base graph {} for stage {}", stage.getBaseGraphId(), stage.getStageId());
            return;
        }

        initializeRedisForStage(stage, graph);
    }

    /**
     * Re-seeds the processed_graph_hashes set from MySQL after Redis data loss.
     * Fetches the most recent INACTIVE stages (up to top-results-count) and adds
     * their base graph hashes, preventing the system from revisiting already-exhausted graphs.
     */
    private void reseedProcessedGraphHashes(Integer campaignId) {
        int count = ramseyConfig.getStage().getCyclePreventionGraphLookbackCount();
        log.info("Re-seeding processed_graph_hashes from last {} INACTIVE stages for campaign {}...", count, campaignId);

        List<Stage> recentStages = middlewareClient.getRecentStagesByCampaignIdAndStatus(
                campaignId, Stage.Status.INACTIVE, count);

        int seeded = 0;
        for (Stage stage : recentStages) {
            Graph graph = middlewareClient.getGraphById(stage.getBaseGraphId());
            if (graph != null && graph.getEdgeData() != null) {
                String hash = GraphHashUtil.computeHash(graph.getEdgeData());
                redisQueueService.addProcessedGraphHash(hash);
                seeded++;
            }
        }

        log.info("Re-seeded {} processed graph hashes from recent stage history", seeded);
    }

    private void initializeRedisForStage(Stage stage, Graph graph) {
        // Calculate total pairs (red edges * blue edges)
        long redCount = 0;
        long blueCount = 0;
        for (int i = 0; i < graph.getEdgeData().length(); i++) {
            if (graph.getEdgeData().charAt(i) == '1') {
                redCount++;
            } else {
                blueCount++;
            }
        }
        long calculatedTotalPairs = computeTotalWorkUnits(redCount, blueCount, stage.getWorkEnumerationStrategy());

        log.info("Initializing counter-based mode for stage {}: redEdges={}, blueEdges={}, totalPairs={}, strategy={}",
                stage.getStageId(), redCount, blueCount, calculatedTotalPairs, stage.getWorkEnumerationStrategy());

        // Initialize Redis with counter and stage config
        redisQueueService.initializeStageCounter(
                stage.getStageId(),
                stage.getBaseGraphId(),
                graph,
                calculatedTotalPairs,
                stage.getWorkEnumerationStrategy());
    }
}

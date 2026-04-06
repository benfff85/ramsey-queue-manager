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

        // Get active stage for the campaign
        List<Stage> stages = middlewareClient.getStagesByCampaignIdAndStatus(
                ramseyConfig.getCampaignId(), Stage.Status.ACTIVE);

        if (stages.size() != 1) {
            log.debug("Expected 1 active stage, found {}", stages.size());
            return;
        }

        Stage currentStage = stages.getFirst();
        Integer stageId = currentStage.getStageId();

        // Get current base graph
        Graph baseGraph = middlewareClient.getGraphById(currentStage.getBaseGraphId());
        if (baseGraph == null || baseGraph.getCliqueCount() == null) {
            log.warn("Could not fetch base graph or clique count for stage {}", stageId);
            return;
        }

        // SCENARIO 1: Check for improvement (best result better than base)
        List<BestResult> topResults = redisQueueService.getTopResults(stageId, 1);
        if (!topResults.isEmpty()) {
            BestResult best = topResults.getFirst();
            if (best.getCliqueCount() < baseGraph.getCliqueCount()) {
                // CRITICAL: Check if this improvement would revert to a previously processed
                // graph
                // This prevents oscillation when exhaustion handling picks a worse graph
                String graphHash;
                if (best.isSimulatedAnnealingResult()) {
                    graphHash = GraphHashUtil.computeHash(best.getGraphBitstring());
                } else {
                    graphHash = GraphHashUtil.computeDerivedGraphHash(baseGraph, best.getEdgesToFlip());
                }

                if (redisQueueService.isGraphAlreadyProcessed(graphHash)) {
                    log.info("Improvement found (cliques={}) but graph already processed, treating as exhaustion case",
                            best.getCliqueCount());
                    // Fall through to exhaustion handling instead of progressing
                } else {
                    log.info("IMPROVEMENT FOUND! Stage {} base graph has {} cliques, best result has {} (source: {})",
                            stageId, baseGraph.getCliqueCount(), best.getCliqueCount(),
                            best.isSimulatedAnnealingResult() ? "SA" : "EXHAUSTIVE");
                    exhaustionDetectedAt.remove(stageId); // Clear any exhaustion tracking
                    progressStageWithHash(currentStage, baseGraph, best, graphHash);
                    return;
                }
            }
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
     * Handle an exhausted stage - wait for delay, then pick best unprocessed
     * result.
     */
    private void handleExhaustedStage(Stage currentStage, Graph baseGraph, Integer stageId) {
        Long delayMs = ramseyConfig.getStage().getExhaustionDelayMs();

        // Track when exhaustion was first detected
        Instant detectedAt = exhaustionDetectedAt.computeIfAbsent(stageId, k -> {
            log.info("Stage {} exhausted, starting {}ms delay before progression...", stageId, delayMs);
            return Instant.now();
        });

        // Check if delay has passed
        long elapsedMs = Instant.now().toEpochMilli() - detectedAt.toEpochMilli();
        if (elapsedMs < delayMs) {
            log.debug("Stage {} exhaustion delay: {}ms / {}ms elapsed", stageId, elapsedMs, delayMs);
            return;
        }

        log.info("Stage {} exhaustion delay complete, selecting best unprocessed result...", stageId);

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
        List<Stage> stages = middlewareClient.getStagesByCampaignIdAndStatus(ramseyConfig.getCampaignId(),
                Stage.Status.ACTIVE);

        if (stages.isEmpty()) {
            return;
        }

        if (stages.size() > 1) {
            log.warn("Expected 1 active stage, found {}. Using the first one.", stages.size());
        }

        Stage stage = stages.getFirst();

        if (stage.getWorkEnumerationStrategy() != null) {
            checkAndInitializeStage(stage);
        }

        // Re-seed processed graph hashes independently — handles the case where only
        // that key was lost while stage_config remained intact
        if (redisQueueService.isProcessedGraphHashesEmpty()) {
            reseedProcessedGraphHashes(stage.getCampaignId());
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
        long calculatedTotalPairs = redCount * blueCount;

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

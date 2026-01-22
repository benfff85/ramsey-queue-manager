package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.BestResult;
import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.Graph;
import com.setminusx.ramsey.qm.model.Stage;
import com.setminusx.ramsey.qm.service.RedisQueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Monitors for improved results and triggers stage progression.
 * When a worker finds a graph with fewer cliques, this creates a new stage.
 */
@Slf4j
@Component
public class StageProgressionMonitor {

    private final MiddlewareClient middlewareClient;
    private final RedisQueueService redisQueueService;
    private final RamseyConfig ramseyConfig;

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

        // Check for best result in Redis
        Optional<BestResult> bestResultOpt = redisQueueService.getBestResult(stageId);
        if (bestResultOpt.isEmpty()) {
            log.debug("No best result found for stage {}", stageId);
            return;
        }

        BestResult bestResult = bestResultOpt.get();

        // Get current base graph to compare
        Graph baseGraph = middlewareClient.getGraphById(currentStage.getBaseGraphId());
        if (baseGraph == null || baseGraph.getCliqueCount() == null) {
            log.warn("Could not fetch base graph or clique count for stage {}", stageId);
            return;
        }

        // Compare: is the best result actually better?
        if (bestResult.getCliqueCount() >= baseGraph.getCliqueCount()) {
            log.debug("Best result {} is not better than base graph {}",
                    bestResult.getCliqueCount(), baseGraph.getCliqueCount());
            return;
        }

        // Found improvement! Trigger stage progression
        log.info("IMPROVEMENT FOUND! Stage {} base graph has {} cliques, best result has {}",
                stageId, baseGraph.getCliqueCount(), bestResult.getCliqueCount());

        try {
            progressStage(currentStage, baseGraph, bestResult);
        } catch (Exception e) {
            log.error("Failed to progress stage: {}", e.getMessage(), e);
        }
    }

    private void progressStage(Stage currentStage, Graph baseGraph, BestResult bestResult) {
        // 1. Get derived graph from middleware
        String edgesToFlipStr = formatEdgesForUrl(bestResult.getEdgesToFlip());
        log.info("Getting derived graph from base {} with edges {}",
                bestResult.getBaseGraphId(), edgesToFlipStr);

        Graph derivedGraph = middlewareClient.getDerivedGraph(
                bestResult.getBaseGraphId(), edgesToFlipStr);

        if (derivedGraph == null) {
            log.error("Failed to get derived graph");
            return;
        }

        // 2. Set the clique count on the derived graph
        derivedGraph.setCliqueCount(bestResult.getCliqueCount());

        // 3. Save the derived graph to DB
        log.info("Saving derived graph with clique count {}", bestResult.getCliqueCount());
        Graph savedGraph = middlewareClient.createGraph(derivedGraph);
        log.info("Created new graph with ID: {}", savedGraph.getGraphId());

        // 4. Mark current stage as INACTIVE
        log.info("Marking stage {} as INACTIVE", currentStage.getStageId());
        currentStage.setStatus(Stage.Status.INACTIVE);
        middlewareClient.updateStage(currentStage);

        // 5. Create new active stage with default enumeration strategy
        Stage newStage = new Stage();
        newStage.setStatus(Stage.Status.ACTIVE);
        newStage.setBaseGraphId(savedGraph.getGraphId());
        newStage.setCampaignId(currentStage.getCampaignId());

        // Apply default work enumeration strategy if configured
        String defaultStrategy = ramseyConfig.getStage().getDefaultWorkEnumerationStrategy();
        if (defaultStrategy != null && !defaultStrategy.isBlank()) {
            newStage.setWorkEnumerationStrategy(defaultStrategy);
            log.info("Setting work enumeration strategy to: {}", defaultStrategy);
        }

        Stage createdStage = middlewareClient.createStage(newStage);
        log.info("Created new stage {} with base graph {}",
                createdStage.getStageId(), savedGraph.getGraphId());

        // 6. Initialize counter-based mode for new stage (before clearing old stage)
        if (createdStage.getWorkEnumerationStrategy() != null) {
            initializeRedisForStage(createdStage, savedGraph);
        }

        // 7. Clear Redis queue and counter keys for old stage
        log.info("Clearing Redis queue, counter keys, and best result for old stage {}", currentStage.getStageId());
        redisQueueService.clearQueue(currentStage.getStageId());
        redisQueueService.clearStageCounter(currentStage.getStageId());
        redisQueueService.deleteBestResult(currentStage.getStageId());

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

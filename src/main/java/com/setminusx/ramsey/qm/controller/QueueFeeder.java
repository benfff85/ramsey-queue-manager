package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.Graph;
import com.setminusx.ramsey.qm.model.Stage;
import com.setminusx.ramsey.qm.model.WorkQueueItem;
import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.EdgePair;
import com.setminusx.ramsey.qm.service.RedisQueueService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Queue Feeder - pushes work items to Redis queue.
 * Uses combined cardinality scoring to prioritize pairs where both edges have
 * high cardinality.
 */
@Slf4j
@Component
public class QueueFeeder {

    private final MiddlewareClient middlewareClient;
    private final RedisQueueService redisQueueService;
    private final RamseyConfig ramseyConfig;
    private Integer graphId;
    private List<Edge> edges;

    // Sorted edge pairs by combined cardinality (red + blue) descending
    private List<EdgePair> sortedPairs;

    // Track position for resuming work generation
    private int lastPairIndex = 0;
    private boolean allWorkCompleted = false;

    public QueueFeeder(MiddlewareClient middlewareClient, RedisQueueService redisQueueService,
            RamseyConfig ramseyConfig) {
        this.middlewareClient = middlewareClient;
        this.redisQueueService = redisQueueService;
        this.ramseyConfig = ramseyConfig;
    }

    @PostConstruct
    private void init() {
        log.info("Initializing edge list");
        edges = new ArrayList<>();
        for (int i = 0; i < ramseyConfig.getVertexCount(); i++) {
            for (int j = i + 1; j < ramseyConfig.getVertexCount(); j++) {
                edges.add(Edge.builder().vertexOne(i).vertexTwo(j).build());
            }
        }
    }

    private void applyGraphAndEdgeColoring(Graph graph) {
        log.info("Setting graph id to: {}", graph.getGraphId());
        graphId = graph.getGraphId();
        log.info("Initializing edge coloring");
        for (int i = 0; i < graph.getEdgeData().length(); i++) {
            edges.get(i).setColoring(graph.getEdgeData().charAt(i));
        }

        // Calculate cardinality and generate sorted pairs
        calculateCardinalityAndGenerateSortedPairs();

        // Reset position when graph changes
        lastPairIndex = 0;
        allWorkCompleted = false;
    }

    /**
     * Calculate cardinality for each edge and generate sorted pairs by combined
     * score.
     * Combined score = red cardinality + blue cardinality.
     * This prioritizes pairs where BOTH edges have high impact potential.
     */
    private void calculateCardinalityAndGenerateSortedPairs() {
        log.info("Calculating edge cardinalities and generating sorted pairs");

        // Build adjacency map: vertex -> list of edges touching that vertex
        Map<Integer, List<Edge>> vertexToEdges = new HashMap<>();
        for (Edge edge : edges) {
            vertexToEdges.computeIfAbsent(edge.getVertexOne(), k -> new ArrayList<>()).add(edge);
            vertexToEdges.computeIfAbsent(edge.getVertexTwo(), k -> new ArrayList<>()).add(edge);
        }

        // Calculate cardinality for each edge
        for (Edge edge : edges) {
            int cardinality = 0;
            char color = edge.getColoring();

            // Count same-colored edges touching vertexOne
            for (Edge neighbor : vertexToEdges.get(edge.getVertexOne())) {
                if (neighbor != edge && neighbor.getColoring() == color) {
                    cardinality++;
                }
            }

            // Count same-colored edges touching vertexTwo
            for (Edge neighbor : vertexToEdges.get(edge.getVertexTwo())) {
                if (neighbor != edge && neighbor.getColoring() == color) {
                    cardinality++;
                }
            }

            edge.setCardinality(cardinality);
        }

        // Separate edges by color
        List<Edge> redEdges = new ArrayList<>();
        List<Edge> blueEdges = new ArrayList<>();

        for (Edge edge : edges) {
            if (edge.getColoring() == '1') {
                redEdges.add(edge);
            } else {
                blueEdges.add(edge);
            }
        }

        log.info("Red edges: {}, Blue edges: {}", redEdges.size(), blueEdges.size());
        log.info("Generating {} total pairs...", (long) redEdges.size() * blueEdges.size());

        // Generate all pairs with combined cardinality score
        sortedPairs = new ArrayList<>(redEdges.size() * blueEdges.size());
        for (Edge red : redEdges) {
            for (Edge blue : blueEdges) {
                int combinedScore = red.getCardinality() + blue.getCardinality();
                sortedPairs.add(EdgePair.builder()
                        .redEdge(red)
                        .blueEdge(blue)
                        .combinedScore(combinedScore)
                        .build());
            }
        }

        // Sort by combined score descending (highest impact potential first)
        sortedPairs.sort((a, b) -> b.getCombinedScore().compareTo(a.getCombinedScore()));

        if (!sortedPairs.isEmpty()) {
            log.info("Generated {} pairs. Max combined score: {}, Min: {}",
                    sortedPairs.size(),
                    sortedPairs.get(0).getCombinedScore(),
                    sortedPairs.get(sortedPairs.size() - 1).getCombinedScore());
        }
    }

    @Scheduled(fixedRateString = "${ramsey.work-unit.queue.frequency-in-millis}")
    public void feedQueue() {
        log.info("Processing feedQueue");

        // Grab the active stage for the campaign
        List<Stage> stages = middlewareClient.getStagesByCampaignIdAndStatus(ramseyConfig.getCampaignId(),
                Stage.Status.ACTIVE);
        if (!(stages.size() == 1)) {
            throw new RuntimeException("Expected 1 active stage, found " + stages.size());
        }
        Stage stage = stages.getFirst();

        // Get queue depth from Redis (O(1)!)
        long queueDepth = redisQueueService.getQueueDepth(stage.getStageId());
        log.info("Current Redis queue depth: {}", queueDepth);

        // Exit if queue is already sufficiently deep
        if (queueDepth >= ramseyConfig.getWorkUnit().getQueue().getDepth().getMin()) {
            log.info("Queue depth {} >= min {}, no work units to create", queueDepth,
                    ramseyConfig.getWorkUnit().getQueue().getDepth().getMin());
            return;
        }

        // Check if all work has already been generated for this graph
        if (allWorkCompleted) {
            log.info("All work units already generated for graph {}, waiting for next stage", graphId);
            return;
        }

        // Determine how many work units to create
        long workUnitCountToCreate = ramseyConfig.getWorkUnit().getQueue().getDepth().getMax() - queueDepth;
        log.info("Work units to create: {}", workUnitCountToCreate);

        // Get the graph for the active stage
        if (!Objects.equals(graphId, stage.getBaseGraphId())) {
            Graph graph = middlewareClient.getGraphById(stage.getBaseGraphId());
            applyGraphAndEdgeColoring(graph);
        }

        // Generate work items from pre-sorted pairs (highest combined cardinality
        // first)
        log.info("Starting from pair index: {}", lastPairIndex);

        List<WorkQueueItem> newWorkItems = new ArrayList<>();
        int batchSize = ramseyConfig.getWorkUnit().getQueue().getDepth().getPublishBatchSize();

        // Iterate through sorted pairs
        for (int i = lastPairIndex; i < sortedPairs.size(); i++) {
            EdgePair pair = sortedPairs.get(i);

            newWorkItems.add(WorkQueueItem.builder()
                    .baseGraphId(graphId)
                    .edgesToFlip(List.of(pair.getRedEdge(), pair.getBlueEdge()))
                    .build());

            // Publish batch if we've reached the batch size
            if (newWorkItems.size() >= batchSize) {
                publishToRedis(newWorkItems, stage.getStageId());
                newWorkItems.clear();
            }

            workUnitCountToCreate--;
            if (workUnitCountToCreate <= 0) {
                // Save position for next run
                lastPairIndex = i + 1;

                if (!newWorkItems.isEmpty()) {
                    publishToRedis(newWorkItems, stage.getStageId());
                }
                log.info("Completed feedQueue, saved position: pairIndex={}", lastPairIndex);
                return;
            }
        }

        // Reached the end of all pairs
        if (!newWorkItems.isEmpty()) {
            log.info("Publishing final batch of new work items for this graph");
            publishToRedis(newWorkItems, stage.getStageId());
        }

        // Mark all work as completed to prevent regeneration
        allWorkCompleted = true;
        log.info("All {} pairs generated for graph id {}. Total work complete.", sortedPairs.size(), graphId);
    }

    private void publishToRedis(List<WorkQueueItem> items, Integer stageId) {
        log.info("Pushing {} work items to Redis queue", items.size());
        redisQueueService.pushWorkItems(stageId, items);
    }

}

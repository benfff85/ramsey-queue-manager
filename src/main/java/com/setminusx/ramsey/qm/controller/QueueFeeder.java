package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.Graph;
import com.setminusx.ramsey.qm.model.Stage;
import com.setminusx.ramsey.qm.model.WorkQueueItem;
import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.service.RedisQueueService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Queue Feeder - pushes work items to Redis queue.
 * Uses heap-based pair generation with bounded memory to prioritize by combined
 * cardinality.
 */
@Slf4j
@Component
public class QueueFeeder {

    private final MiddlewareClient middlewareClient;
    private final RedisQueueService redisQueueService;
    private final RamseyConfig ramseyConfig;
    private Integer graphId;
    private List<Edge> edges;

    // Sorted edge lists by cardinality (descending)
    private List<Edge> sortedRedEdges = new ArrayList<>();
    private List<Edge> sortedBlueEdges = new ArrayList<>();
    private long totalPairs = 0;

    // Bounded heap-based generator: track next blue index per red row
    // This uses O(redEdges) memory, not O(pairs)
    private PriorityQueue<int[]> pairHeap; // [redIndex, blueIndex, combinedScore]
    private int[] nextBlueForRed; // For each red index, track next blue to consider
    private long pairsGenerated = 0;
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

        calculateCardinalityAndInitializeHeap();

        pairsGenerated = 0;
        allWorkCompleted = false;
    }

    /**
     * Calculate cardinality for each edge and initialize bounded heap generator.
     * Memory: O(redEdges) for the heap + O(redEdges) for nextBlueForRed array.
     */
    private void calculateCardinalityAndInitializeHeap() {
        log.info("Calculating edge cardinalities");

        Map<Integer, List<Edge>> vertexToEdges = new HashMap<>();
        for (Edge edge : edges) {
            vertexToEdges.computeIfAbsent(edge.getVertexOne(), k -> new ArrayList<>()).add(edge);
            vertexToEdges.computeIfAbsent(edge.getVertexTwo(), k -> new ArrayList<>()).add(edge);
        }

        for (Edge edge : edges) {
            int cardinality = 0;
            char color = edge.getColoring();

            for (Edge neighbor : vertexToEdges.get(edge.getVertexOne())) {
                if (neighbor != edge && neighbor.getColoring() == color) {
                    cardinality++;
                }
            }

            for (Edge neighbor : vertexToEdges.get(edge.getVertexTwo())) {
                if (neighbor != edge && neighbor.getColoring() == color) {
                    cardinality++;
                }
            }

            edge.setCardinality(cardinality);
        }

        // Separate and sort edges by cardinality (descending)
        sortedRedEdges = edges.stream()
                .filter(e -> e.getColoring() == '1')
                .sorted((a, b) -> b.getCardinality().compareTo(a.getCardinality()))
                .toList();

        sortedBlueEdges = edges.stream()
                .filter(e -> e.getColoring() == '0')
                .sorted((a, b) -> b.getCardinality().compareTo(a.getCardinality()))
                .toList();

        totalPairs = (long) sortedRedEdges.size() * sortedBlueEdges.size();
        log.info("Red edges: {}, Blue edges: {}, Total pairs: {}",
                sortedRedEdges.size(), sortedBlueEdges.size(), totalPairs);

        // Initialize heap with first pair from each red edge row
        // Max-heap by combined score
        pairHeap = new PriorityQueue<>((a, b) -> b[2] - a[2]);
        nextBlueForRed = new int[sortedRedEdges.size()];
        Arrays.fill(nextBlueForRed, 0);

        // Seed heap with (red[i], blue[0]) for all red edges
        if (!sortedBlueEdges.isEmpty()) {
            for (int ri = 0; ri < sortedRedEdges.size(); ri++) {
                int score = sortedRedEdges.get(ri).getCardinality()
                        + sortedBlueEdges.get(0).getCardinality();
                pairHeap.offer(new int[] { ri, 0, score });
                nextBlueForRed[ri] = 1; // Mark that blue[0] is in heap
            }
        }
    }

    /**
     * Get the next pair in combined-score order.
     * When we pop (ri, bi), we push (ri, bi+1) to maintain one entry per red row.
     */
    private int[] getNextPair() {
        if (pairHeap.isEmpty()) {
            return null;
        }

        int[] current = pairHeap.poll();
        int ri = current[0];

        // Push the next blue for this red row if available
        int nextBi = nextBlueForRed[ri];
        if (nextBi < sortedBlueEdges.size()) {
            int score = sortedRedEdges.get(ri).getCardinality()
                    + sortedBlueEdges.get(nextBi).getCardinality();
            pairHeap.offer(new int[] { ri, nextBi, score });
            nextBlueForRed[ri] = nextBi + 1;
        }

        return current;
    }

    @Scheduled(fixedRateString = "${ramsey.work-unit.queue.frequency-in-millis}")
    public void feedQueue() {
        long feedQueueStart = System.currentTimeMillis();
        log.info("Processing feedQueue");

        List<Stage> stages = middlewareClient.getStagesByCampaignIdAndStatus(ramseyConfig.getCampaignId(),
                Stage.Status.ACTIVE);
        if (!(stages.size() == 1)) {
            throw new RuntimeException("Expected 1 active stage, found " + stages.size());
        }
        Stage stage = stages.getFirst();

        long queueDepth = redisQueueService.getQueueDepth(stage.getStageId());
        log.info("Current Redis queue depth: {}", queueDepth);

        if (queueDepth >= ramseyConfig.getWorkUnit().getQueue().getDepth().getMin()) {
            log.info("Queue depth {} >= min {}, no work units to create", queueDepth,
                    ramseyConfig.getWorkUnit().getQueue().getDepth().getMin());
            return;
        }

        if (allWorkCompleted) {
            log.info("All work units already generated for graph {}, waiting for next stage", graphId);
            return;
        }

        long workUnitCountToCreate = ramseyConfig.getWorkUnit().getQueue().getDepth().getMax() - queueDepth;
        log.info("Work units to create: {}", workUnitCountToCreate);

        if (!Objects.equals(graphId, stage.getBaseGraphId())) {
            Graph graph = middlewareClient.getGraphById(stage.getBaseGraphId());
            applyGraphAndEdgeColoring(graph);
        }

        log.info("Pairs generated so far: {}/{}", pairsGenerated, totalPairs);

        int batchSize = ramseyConfig.getWorkUnit().getQueue().getDepth().getPublishBatchSize();
        List<WorkQueueItem> newWorkItems = new ArrayList<>(batchSize);
        long totalGenerationTimeMs = 0;
        long totalPushTimeMs = 0;
        int batchesPushed = 0;
        long batchGenStart = System.currentTimeMillis();

        while (workUnitCountToCreate > 0) {
            int[] pair = getNextPair();
            if (pair == null) {
                break;
            }

            Edge redEdge = sortedRedEdges.get(pair[0]);
            Edge blueEdge = sortedBlueEdges.get(pair[1]);

            newWorkItems.add(WorkQueueItem.builder()
                    .baseGraphId(graphId)
                    .edgesToFlip(List.of(redEdge, blueEdge))
                    .build());

            pairsGenerated++;

            if (newWorkItems.size() >= batchSize) {
                totalGenerationTimeMs += System.currentTimeMillis() - batchGenStart;
                long pushStart = System.currentTimeMillis();
                publishToRedis(newWorkItems, stage.getStageId());
                totalPushTimeMs += System.currentTimeMillis() - pushStart;
                batchesPushed++;
                newWorkItems.clear();
                batchGenStart = System.currentTimeMillis();
            }

            workUnitCountToCreate--;
        }

        if (!newWorkItems.isEmpty()) {
            totalGenerationTimeMs += System.currentTimeMillis() - batchGenStart;
            long pushStart = System.currentTimeMillis();
            publishToRedis(newWorkItems, stage.getStageId());
            totalPushTimeMs += System.currentTimeMillis() - pushStart;
            batchesPushed++;
        }

        if (pairHeap.isEmpty()) {
            allWorkCompleted = true;
            log.info("All {} pairs generated for graph id {}. Total work complete.", pairsGenerated, graphId);
        } else {
            log.info("Completed feedQueue, pairs generated: {}/{}", pairsGenerated, totalPairs);
        }

        long totalFeedQueueMs = System.currentTimeMillis() - feedQueueStart;
        log.info("feedQueue timing: total={}ms, generation={}ms, redisPush={}ms, batches={}",
                totalFeedQueueMs, totalGenerationTimeMs, totalPushTimeMs, batchesPushed);
    }

    private void publishToRedis(List<WorkQueueItem> items, Integer stageId) {
        log.info("Pushing {} work items to Redis queue", items.size());
        redisQueueService.pushWorkItems(stageId, items);
    }

}

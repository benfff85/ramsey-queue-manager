package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.Graph;
import com.setminusx.ramsey.qm.model.Stage;
import com.setminusx.ramsey.qm.model.WorkQueueItem;
import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.WorkUnitAnalysisType;
import com.setminusx.ramsey.qm.service.RedisQueueService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Queue Feeder - pushes work items to Redis queue.
 * Replaces the old MySQL-based work unit creation.
 */
@Slf4j
@Component
public class QueueFeeder {

    private final MiddlewareClient middlewareClient;
    private final RedisQueueService redisQueueService;
    private final RamseyConfig ramseyConfig;
    private Integer graphId;
    private List<Edge> edges;

    // Track position for resuming work generation
    private int lastLeftEdgeIndex = 0;
    private int lastRightEdgeIndex = 0;

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
        // Reset position when graph changes
        lastLeftEdgeIndex = 0;
        lastRightEdgeIndex = 0;
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

        // Determine how many work units to create
        long workUnitCountToCreate = ramseyConfig.getWorkUnit().getQueue().getDepth().getMax() - queueDepth;
        log.info("Work units to create: {}", workUnitCountToCreate);

        // Get the graph for the active stage
        if (!Objects.equals(graphId, stage.getBaseGraphId())) {
            Graph graph = middlewareClient.getGraphById(stage.getBaseGraphId());
            applyGraphAndEdgeColoring(graph);
        }

        // Generate new work items
        int leftEdgeIndex = lastLeftEdgeIndex;
        int rightEdgeIndex = lastRightEdgeIndex;

        log.info("Starting from left edge index: {}, right edge index: {}", leftEdgeIndex, rightEdgeIndex);

        List<WorkQueueItem> newWorkItems = new ArrayList<>();
        List<WorkUnitAnalysisType> analysisTypes = ramseyConfig.getWorkUnit().getQueue().getAnalysisType();
        int batchSize = ramseyConfig.getWorkUnit().getQueue().getDepth().getPublishBatchSize();

        for (int i = leftEdgeIndex; i < edges.size() - 1; i++) {
            Edge leftEdge = edges.get(i);
            int startJ = (i == leftEdgeIndex) ? rightEdgeIndex + 1 : i + 1;

            for (int j = startJ; j < edges.size(); j++) {
                Edge rightEdge = edges.get(j);

                if (leftEdge.getColoring() != rightEdge.getColoring()) {
                    for (WorkUnitAnalysisType analysisType : analysisTypes) {
                        newWorkItems.add(WorkQueueItem.builder()
                                .baseGraphId(graphId)
                                .stageId(stage.getStageId())
                                .edgesToFlip(List.of(leftEdge, rightEdge))
                                .analysisType(analysisType)
                                .build());
                    }

                    // Publish batch if we've reached the batch size
                    if (newWorkItems.size() >= batchSize) {
                        publishToRedis(newWorkItems, stage.getStageId());
                        newWorkItems.clear();
                    }

                    workUnitCountToCreate--;
                    if (workUnitCountToCreate <= 0) {
                        // Save position for next run
                        lastLeftEdgeIndex = i;
                        lastRightEdgeIndex = j;

                        if (!newWorkItems.isEmpty()) {
                            publishToRedis(newWorkItems, stage.getStageId());
                        }
                        log.info("Completed feedQueue, saved position: left={}, right={}", i, j);
                        return;
                    }
                }
            }
        }

        // Reached the end of all edge combinations
        if (!newWorkItems.isEmpty()) {
            log.info("Publishing final batch of new work items for this graph");
            publishToRedis(newWorkItems, stage.getStageId());
        }

        log.warn("No more edge combinations available for graph id {}", graphId);
    }

    private void publishToRedis(List<WorkQueueItem> items, Integer stageId) {
        log.info("Pushing {} work items to Redis queue", items.size());
        redisQueueService.pushWorkItems(stageId, items);
    }

}

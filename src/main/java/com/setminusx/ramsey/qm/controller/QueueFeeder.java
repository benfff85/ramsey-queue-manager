package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.Graph;
import com.setminusx.ramsey.qm.model.Stage;
import com.setminusx.ramsey.qm.model.WorkUnit;
import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.WorkUnitAnalysisType;
import com.setminusx.ramsey.qm.model.WorkUnitStatus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.setminusx.ramsey.qm.model.WorkUnitAnalysisType.*;
import static com.setminusx.ramsey.qm.model.WorkUnitPriority.MEDIUM;
import static com.setminusx.ramsey.qm.model.WorkUnitStatus.NEW;
import static com.setminusx.ramsey.qm.utility.TimeUtility.now;
import static java.util.Arrays.asList;
import static java.util.Objects.nonNull;
import static org.springframework.util.CollectionUtils.isEmpty;

@Slf4j
@Component
public class QueueFeeder {

    private final MiddlewareClient middlewareClient;
    private final RamseyConfig ramseyConfig;
    private Integer graphId;
    private List<Edge> edges;

    public QueueFeeder(MiddlewareClient middlewareClient, RamseyConfig ramseyConfig) {
        this.middlewareClient = middlewareClient;
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
    }


    @Scheduled(fixedRateString = "${ramsey.work-unit.queue.frequency-in-millis}")
    public void feedQueue() {

        log.info("Processing feedQueue");

        // Grab the active stage for the campaign
        List<Stage> stages = middlewareClient.getStagesByCampaignIdAndStatus(ramseyConfig.getCampaignId(), Stage.Status.ACTIVE);
        if (!(stages.size() == 1)) {
            throw new RuntimeException("Expected 1 active stage, found " + stages.size());
        }
        Stage stage = stages.getFirst();

        // Get count of unassigned work units for stage
        int unassignedWorkUnitCount = middlewareClient.getWorkUnitsByStageIdAndStatus(stage.getStageId(), WorkUnitStatus.NEW, ramseyConfig.getWorkUnit().getQueue().getDepth().getMin()).size();

        // Exit if queue is already sufficiently deep
        if (unassignedWorkUnitCount >= ramseyConfig.getWorkUnit().getQueue().getDepth().getMin()) {
            log.info("No work units to create, exiting");
            return;
        }

        // Determine how many work units to create
        int workUnitCountToCreate = ramseyConfig.getWorkUnit().getQueue().getDepth().getMax() - unassignedWorkUnitCount;
        log.info("Work units to create: {}", workUnitCountToCreate);

        // Get the graph for the active stage
        if (!Objects.equals(graphId, stage.getBaseGraphId())) {
            Graph graph = middlewareClient.getGraphById(stage.getBaseGraphId());
            applyGraphAndEdgeColoring(graph);
        }

        // Generate new work units
        int leftEdgeIndex = 0;
        int rightEdgeIndex = 0;
        if (nonNull(stage.getLatestWorkUnitId())) {
            log.info("Getting edge data from last work unit");
            WorkUnit lastWorkUnit = middlewareClient.getWorkUnitById(stage.getLatestWorkUnitId());
            leftEdgeIndex = edges.indexOf(lastWorkUnit.getEdgesToFlip().get(0));
            rightEdgeIndex = edges.indexOf(lastWorkUnit.getEdgesToFlip().get(1));
        }
        log.info("Left edge index: {}", leftEdgeIndex);
        log.info("Right edge index: {}", rightEdgeIndex);

        log.info("Creating work units...");
        List<WorkUnit> newWorkUnits = new ArrayList<>();
        LocalDateTime now = now();

        List<WorkUnitAnalysisType> analysisType = ramseyConfig.getWorkUnit().getQueue().getAnalysisType();
        for (int i = leftEdgeIndex; i < edges.size() - 1; i++) {
            Edge leftEdge = edges.get(i);
            for (int j = rightEdgeIndex + 1; j < edges.size(); j++) {
                Edge rightEdge = edges.get(j);
                if (leftEdge.getColoring() != rightEdge.getColoring()) {
                    if (analysisType.contains(NAIVE)) {
                        createWorkUnit(newWorkUnits, leftEdge, rightEdge, now, NAIVE, stage.getStageId());
                    }
                    if (analysisType.contains(COMPREHENSIVE)) {
                        createWorkUnit(newWorkUnits, leftEdge, rightEdge, now, COMPREHENSIVE, stage.getStageId());
                    }
                    if (analysisType.contains(TARGETED)) {
                        createWorkUnit(newWorkUnits, leftEdge, rightEdge, now, TARGETED, stage.getStageId());
                    }
                    if (--workUnitCountToCreate == 0) {
                        publishNewWorkUnits(newWorkUnits, stage);
                        return;
                    }
                }
            }
            rightEdgeIndex = i + 1;
        }

        if (!isEmpty(newWorkUnits)) {
            log.info("Publishing final batch of new work units for this graph");
            publishNewWorkUnits(newWorkUnits, stage);
            return;
        }

        log.warn("No work units left to create for graph id {}", graphId);
    }

    private void createWorkUnit(List<WorkUnit> newWorkUnits, Edge leftEdge, Edge rightEdge, LocalDateTime now, WorkUnitAnalysisType analysisType, int stageId) {
        newWorkUnits.add(WorkUnit.builder()
                .baseGraphId(graphId)
                .stageId(stageId)
                .edgesToFlip(asList(leftEdge, rightEdge))
                .vertexCount(ramseyConfig.getVertexCount())
                .subgraphSize(ramseyConfig.getSubgraphSize())
                .createdDate(now)
                .priority(MEDIUM)
                .workUnitAnalysisType(analysisType)
                .status(NEW)
                .build());
    }

    private void publishNewWorkUnits(List<WorkUnit> newWorkUnits, Stage stage) {
        log.info("Work units created: {}", newWorkUnits.size());
        log.info("Publishing work units");
        List<WorkUnit> createdWorkUnits = middlewareClient.createWorkUnits(newWorkUnits);
        stage.setLatestWorkUnitId(createdWorkUnits.getLast().getId());
        stage.setUpdatedDate(now());
        middlewareClient.updateStage(stage);
        log.info("Completed feedQueue");
    }

}

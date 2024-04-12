package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.dto.GraphDto;
import com.setminusx.ramsey.qm.dto.WorkUnitDto;
import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.WorkUnitAnalysisType;
import com.setminusx.ramsey.qm.service.GraphService;
import com.setminusx.ramsey.qm.service.WorkUnitService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.setminusx.ramsey.qm.model.WorkUnitAnalysisType.*;
import static com.setminusx.ramsey.qm.model.WorkUnitPriority.MEDIUM;
import static com.setminusx.ramsey.qm.model.WorkUnitStatus.CANCELLED;
import static com.setminusx.ramsey.qm.model.WorkUnitStatus.NEW;
import static com.setminusx.ramsey.qm.utility.TimeUtility.now;
import static java.util.Arrays.asList;
import static java.util.Objects.nonNull;
import static org.springframework.util.CollectionUtils.isEmpty;

@Slf4j
@Component
public class QueueFeeder {

    @Value("${ramsey.work-unit.queue.depth.min}")
    private Integer queueDepthMin;

    @Value("${ramsey.work-unit.queue.depth.max}")
    private Integer queueDepthMax;

    @Value("${ramsey.work-unit.queue.analysis-type}")
    private List<WorkUnitAnalysisType> analysisType;

    @Value("${ramsey.vertex-count}")
    private Integer vertexCount;

    @Value("${ramsey.subgraph-size}")
    private Integer subgraphSize;

    private GraphDto graph;
    private List<Edge> edges;

    private final GraphService graphService;
    private final WorkUnitService workUnitService;

    public QueueFeeder(GraphService graphService, WorkUnitService workUnitService) {
        this.graphService = graphService;
        this.workUnitService = workUnitService;
    }

    @PostConstruct
    private void init() {
        log.info("Initializing edge list");
        edges = new ArrayList<>();
        for (int i = 0; i < vertexCount; i++) {
            for (int j = i + 1; j < vertexCount; j++) {
                edges.add(Edge.builder().vertexOne(i).vertexTwo(j).build());
            }
        }
        applyGraphAndEdgeColoring(graphService.getMin());
    }


    private void applyGraphAndEdgeColoring(GraphDto graph) {
        log.info("Setting graph to graph id: {}", graph.getGraphId());
        this.graph = graph;
        log.info("Initializing edge coloring");
        for (int i = 0; i < graph.getEdgeData().length(); i++) {
            edges.get(i).setColoring(graph.getEdgeData().charAt(i));
        }
    }


    @Scheduled(fixedRateString = "${ramsey.work-unit.queue.frequency-in-millis}")
    public void feedQueue() {

        log.info("Processing feedQueue");

        GraphDto currentMinGraph = graphService.getMin();
        List<WorkUnitDto> workUnits = workUnitService.getUnassignedWorkUnits(50000);
        if (!graph.getGraphId().equals(currentMinGraph.getGraphId())) {
            cancelAllOpenWorkUnits(workUnits);
            applyGraphAndEdgeColoring(currentMinGraph);
            workUnits.clear();
        }

        if (workUnits.size() >= queueDepthMin) {
            log.info("No work units to create, exiting");
            return;
        }

        int workUnitCountToCreate = queueDepthMax - workUnits.size();
        log.info("Work units to create: {}", workUnitCountToCreate);

        WorkUnitDto lastWorkUnit = workUnitService.getLast(graph.getGraphId());
        int leftEdgeIndex = 0;
        int rightEdgeIndex = 0;
        if (nonNull(lastWorkUnit)) {
            log.info("Getting edge data from last work unit");
            leftEdgeIndex = edges.indexOf(lastWorkUnit.getEdgesToFlip().get(0));
            rightEdgeIndex = edges.indexOf(lastWorkUnit.getEdgesToFlip().get(1));
        }
        log.info("Left edge index: {}", leftEdgeIndex);
        log.info("Right edge index: {}", rightEdgeIndex);

        log.info("Creating work units...");
        List<WorkUnitDto> newWorkUnits = new ArrayList<>();
        LocalDateTime now = now();

        for (int i = leftEdgeIndex; i < graph.getEdgeData().length() - 1; i++) {
            Edge leftEdge = edges.get(i);
            for (int j = rightEdgeIndex + 1; j < graph.getEdgeData().length(); j++) {
                Edge rightEdge = edges.get(j);
                if (leftEdge.getColoring() != rightEdge.getColoring()) {
                    if(analysisType.contains(NAIVE)) {
                        createWorkUnit(newWorkUnits, leftEdge, rightEdge, now, NAIVE);
                    }
                    if(analysisType.contains(COMPREHENSIVE)) {
                        createWorkUnit(newWorkUnits, leftEdge, rightEdge, now, COMPREHENSIVE);
                    }
                    if(analysisType.contains(TARGETED)) {
                        createWorkUnit(newWorkUnits, leftEdge, rightEdge, now, TARGETED);
                    }
                    if (--workUnitCountToCreate == 0) {
                        publishNewWorkUnits(newWorkUnits);
                        return;
                    }
                }
            }
            rightEdgeIndex = i + 1;
        }

        if (!isEmpty(newWorkUnits)) {
            log.info("Publishing final batch of new work units for this graph");
            publishNewWorkUnits(newWorkUnits);
            return;
        }

        log.warn("No work units left to create for graph id {}", graph.getGraphId());
    }

    private void createWorkUnit(List<WorkUnitDto> newWorkUnits, Edge leftEdge, Edge rightEdge, LocalDateTime now, WorkUnitAnalysisType analysisType) {
        newWorkUnits.add(WorkUnitDto.builder()
                .baseGraphId(graph.getGraphId())
                .edgesToFlip(asList(leftEdge, rightEdge))
                .vertexCount(vertexCount)
                .subgraphSize(subgraphSize)
                .createdDate(now)
                .priority(MEDIUM)
                .workUnitAnalysisType(analysisType)
                .status(NEW)
                .build());
    }

    private void publishNewWorkUnits(List<WorkUnitDto> newWorkUnits) {
        log.info("Work units created: {}", newWorkUnits.size());
        log.info("Publishing work units");
        workUnitService.save(newWorkUnits);
        log.info("Completed feedQueue");
    }

    private void cancelAllOpenWorkUnits(List<WorkUnitDto> workUnits) {
        log.info("Cancelling all open work units");
        workUnits.forEach(wu -> wu.setStatus(CANCELLED));
        workUnitService.save(workUnits);
    }

}

package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.dto.GraphDto;
import com.setminusx.ramsey.qm.dto.WorkUnitDto;
import com.setminusx.ramsey.qm.exception.RemoteCallException;
import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.WorkUnitStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Objects.isNull;

@Slf4j
@Component
public class QueueFeeder {

    @Value("${ramsey.graph.url}")
    private String graphUrl;

    @Value("${ramsey.work-unit.queue.url}")
    private String workUnitUrl;

    @Value("${ramsey.work-unit.queue.depth.min}")
    private Integer queueDepthMin;

    @Value("${ramsey.work-unit.queue.depth.max}")
    private Integer queueDepthMax;

    @Value("${ramsey.vertex-count}")
    private Integer vertexCount;

    @Value("${ramsey.subgraph-size}")
    private Integer subgraphSize;

    private RestTemplate restTemplate = new RestTemplate();
    private String minGraphUri;
    private String unassignedWorkUnitUri;

    private GraphDto graph;
    private List<Edge> edges;

    @PostConstruct
    private void init() {
        createUris();
        pullInitialGraph();
    }

    private void createUris() {
        minGraphUri = UriComponentsBuilder.fromHttpUrl(graphUrl)
                .queryParam("type", "min")
                .queryParam("vertexCount", vertexCount)
                .queryParam("subgraphSize", subgraphSize)
                .toUriString();

        unassignedWorkUnitUri = UriComponentsBuilder.fromHttpUrl(workUnitUrl)
                .queryParam("status", WorkUnitStatus.NEW)
                .queryParam("vertexCount", vertexCount)
                .queryParam("subgraphSize", subgraphSize)
                .queryParam("pageSize", 500000)
                .toUriString();
    }

    private void pullInitialGraph() {
        log.info("Initializing edge list");
        edges = new ArrayList<>();
        for (int i = 0; i < vertexCount; i++) {
            for (int j = i + 1; j < vertexCount; j++) {
                edges.add(Edge.builder().vertexOne(i).vertexTwo(j).build());
            }
        }
        applyGraphAndEdgeColoring(getMinGraph());
    }

    private void applyGraphAndEdgeColoring(GraphDto graph) {
        log.info("Setting graph to graph id: {}", graph.getGraphId());
        this.graph = graph;
        log.info("Initializing edge coloring");
        for (int i = 0; i < graph.getEdgeData().length(); i++) {
            edges.get(i).setColoring(graph.getEdgeData().charAt(i));
        }
    }

    private GraphDto getMinGraph() {
        log.info("Fetching min graph");
        GraphDto[] graphDtos = restTemplate.getForObject(minGraphUri, GraphDto[].class);
        if (isNull(graphDtos) || graphDtos.length != 1 || isNull(graphDtos[0])) {
            throw new RemoteCallException("Error when fetching initial min graph");
        }

        log.info("Min graph id: {}", graphDtos[0].getGraphId());
        return graphDtos[0];
    }

    @Scheduled(fixedRateString = "${ramsey.work-unit.queue.frequency-in-millis}")
    public void feedQueue() {

        log.info("Processing feedQueue");

        GraphDto currentMinGraph = getMinGraph();
        List<WorkUnitDto> workUnits = getUnassignedWorkUnits();
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


        int leftEdgeIndex = 0;
        int rightEdgeIndex = 1;
        if (!workUnits.isEmpty()) {
            log.info("Getting edge data from last existing work unit");
            WorkUnitDto lastWorkUnit = workUnits.get(workUnits.size() - 1);
            leftEdgeIndex = edges.indexOf(lastWorkUnit.getEdgesToFlip().get(0));
            log.info("Left edge index of last work unit: {}", leftEdgeIndex);
            rightEdgeIndex = edges.indexOf(lastWorkUnit.getEdgesToFlip().get(1));
            log.info("Right edge index of last work unit: {}", rightEdgeIndex);
        }

        log.info("Creating work units...");
        List<WorkUnitDto> newWorkUnits = new ArrayList<>();
        outerloop:
        for (int i = leftEdgeIndex; i < graph.getEdgeData().length() - 1; i++) {
            Edge leftEdge = edges.get(i);
            for (int j = rightEdgeIndex; j < graph.getEdgeData().length(); j++) {
                Edge rightEdge = edges.get(j);
                if (leftEdge.getColoring() != rightEdge.getColoring()) {
                    newWorkUnits.add(WorkUnitDto.builder()
                            .baseGraphId(graph.getGraphId())
                            .edgesToFlip(new ArrayList<>(Arrays.asList(leftEdge, rightEdge)))
                            .vertexCount(vertexCount)
                            .subgraphSize(subgraphSize)
                            .build());

                    if (--workUnitCountToCreate == 0) {
                        break outerloop;
                    }
                }
            }
        }

        log.info("Work units created: {}", newWorkUnits.size());
        log.info("Publishing work units");
        restTemplate.postForObject(unassignedWorkUnitUri, newWorkUnits, WorkUnitDto[].class);
        log.info("Completed feedQueue");

    }


    private List<WorkUnitDto> getUnassignedWorkUnits() {
        log.info("Fetching unassigned work units");
        WorkUnitDto[] workUnitDtos = restTemplate.getForObject(unassignedWorkUnitUri, WorkUnitDto[].class);
        if (isNull(workUnitDtos)) {
            throw new RemoteCallException("Error when fetching unassigned work units");
        }
        log.info("Unassigned work unit count: {}", workUnitDtos.length);
        return new ArrayList<>(Arrays.asList(workUnitDtos));
    }

    private void cancelAllOpenWorkUnits(List<WorkUnitDto> workUnits) {
        log.info("Cancelling all open work units");
        for (WorkUnitDto workUnit : workUnits) {
            workUnit.setStatus(WorkUnitStatus.CANCELLED);
        }
        restTemplate.postForObject(unassignedWorkUnitUri, workUnits, WorkUnitDto[].class);
    }

}

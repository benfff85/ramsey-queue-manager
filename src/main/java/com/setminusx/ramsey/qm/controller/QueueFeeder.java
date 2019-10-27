package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.dto.GraphDto;
import com.setminusx.ramsey.qm.dto.WorkUnitDto;
import com.setminusx.ramsey.qm.model.WorkUnitStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.util.List;

@Slf4j

@Component
public class QueueFeeder {

    @Value("${ramsey.graph.url}")
    private String graphUrl;

    @Value("${ramsey.work-unit.queue.url}")
    private String workUnitUrl;

    @Value("${ramsey.work-unit.queue.depth}")
    private String queueDepth;

    @Value("${ramsey.vertex-count}")
    private Integer vertexCount;

    @Value("${ramsey.subgraph-size}")
    private Integer subgraphSize;

    private RestTemplate restTemplate = new RestTemplate();
    private String minGraphUri;
    private String unassignedWorkUnitUri;

    private Integer graphId;

    @PostConstruct
    public void register() {
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

    @Scheduled(fixedRateString = "${ramsey.client.registration.phone-home.frequency-in-millis}")
    public void feedQueue() {

        log.info("Processing feedQueue");

        GraphDto graph = getMinGraph();
        if (graphId == null) {
            graphId = graph.getGraphId();
        }

        List<WorkUnitDto> workUnits = getUnassignedWorkUnits();
        int currentQueueDepth = workUnits.size();
        if (!graphId.equals(graph.getGraphId())) {
            cancelAllOpenWorkUnits(workUnits);
            currentQueueDepth = 0;
        }

        // TODO Complete flow

        log.info("Completed feedQueue");

    }

    private GraphDto getMinGraph() {
        log.info("Fetching min graph");
        ResponseEntity<List<GraphDto>> response = restTemplate.exchange(minGraphUri, HttpMethod.GET, null, new ParameterizedTypeReference<List<GraphDto>>(){});
        GraphDto graph = response.getBody().get(0);
        log.info("Min graph id: {}", graph.getGraphId());
        return graph;
    }

    private List<WorkUnitDto> getUnassignedWorkUnits() {
        log.info("Fetching unassigned work units");
        ResponseEntity<List<WorkUnitDto>> response =
                restTemplate.exchange(unassignedWorkUnitUri, HttpMethod.GET, null, new ParameterizedTypeReference<List<WorkUnitDto>>(){});
        List<WorkUnitDto> workUnits = response.getBody();
        log.info("Unassigned work unit count: {}", workUnits.size());
        return workUnits;
    }

    private void cancelAllOpenWorkUnits(List<WorkUnitDto> workUnits) {
        log.info("Cancelling all open work units");
        for (WorkUnitDto workUnit : workUnits) {
            workUnit.setStatus(WorkUnitStatus.CANCELLED);
        }
        HttpEntity<List<WorkUnitDto>> request = new HttpEntity<>(workUnits);
        restTemplate.exchange(unassignedWorkUnitUri, HttpMethod.POST, request, new ParameterizedTypeReference<List<WorkUnitDto>>(){});
    }

}

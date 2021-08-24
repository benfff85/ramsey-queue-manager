package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.dto.WorkUnitDto;
import com.setminusx.ramsey.qm.model.WorkUnitStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.isNull;

@Slf4j
@Component
public class WorkUnitService {

    @Value("${ramsey.work-unit.queue.url}")
    private String workUnitUrl;

    @Value("${ramsey.vertex-count}")
    private Integer vertexCount;

    @Value("${ramsey.subgraph-size}")
    private Integer subgraphSize;

    @Value("${ramsey.work-unit.assignment.count-per-client}")
    private Integer workUnitCountPerClient;


    private final RestTemplate restTemplate;
    private String workUnitUri;

    public WorkUnitService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    private void createUris() {
        workUnitUri = UriComponentsBuilder.fromHttpUrl(workUnitUrl)
                .queryParam("subgraphSize", subgraphSize)
                .queryParam("vertexCount", vertexCount)
                .toUriString();

    }


    public List<WorkUnitDto> getWorkUnitsAssignedToClient(String clientId) {
        log.info("Fetching work units assigned with client id {}", clientId);
        String uri = UriComponentsBuilder.fromHttpUrl(workUnitUri).queryParam("assignedClientId", clientId).queryParam("status", WorkUnitStatus.ASSIGNED).queryParam("pageSize", workUnitCountPerClient).toUriString();
        WorkUnitDto[] workUnits = restTemplate.getForObject(uri, WorkUnitDto[].class);
        if (isNull(workUnits) || workUnits.length == 0) {
            log.info("No assigned work units");
            return Collections.emptyList();
        }

        log.info("Found {} assigned work units", workUnits.length);
        return Arrays.asList(workUnits);
    }

    public List<WorkUnitDto> getUnassignedWorkUnits(Integer count) {
        log.info("Fetching {} unassigned work units", count);
        String uri = UriComponentsBuilder.fromHttpUrl(workUnitUri).queryParam("status", WorkUnitStatus.NEW).queryParam("pageSize", count).toUriString();
        WorkUnitDto[] workUnits = restTemplate.getForObject(uri, WorkUnitDto[].class);
        if (isNull(workUnits) || workUnits.length == 0) {
            log.info("No unassigned work units");
            return Collections.emptyList();
        }

        log.info("Found {} unassigned work units", workUnits.length);
        return Arrays.asList(workUnits);
    }

    public WorkUnitDto getLast(Integer graphId) {
        log.info("Fetching last work unit for graph id {}", graphId);
        URI uri = UriComponentsBuilder.fromHttpUrl(workUnitUrl + "/last").queryParam("graphId", graphId).build().toUri();
        WorkUnitDto[] workUnits = restTemplate.getForObject(uri, WorkUnitDto[].class);
        if (isNull(workUnits) || workUnits.length == 0) {
            log.info("No last work unit");
            return null;
        }

        return workUnits[0];
    }

    public void save(List<WorkUnitDto> workUnits) {
        restTemplate.postForObject(workUnitUrl, workUnits, WorkUnitDto[].class);
    }

}

package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.dto.ClientDto;
import com.setminusx.ramsey.qm.dto.WorkUnitDto;
import com.setminusx.ramsey.qm.model.WorkUnitStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
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


    private RestTemplate restTemplate;
    private ClientService clientService;
    private String workUnitUri;

    public WorkUnitService(RestTemplate restTemplate, ClientService clientService) {
        this.restTemplate = restTemplate;
        this.clientService = clientService;
    }

    @PostConstruct
    private void createUris() {
        workUnitUri = UriComponentsBuilder.fromHttpUrl(workUnitUrl)
                .queryParam("subgraphSize", subgraphSize)
                .queryParam("vertexCount", vertexCount)
                .toUriString();
    }

    @Scheduled(fixedRateString = "${ramsey.work-unit.assignment.frequency-in-millis}")
    public void assignWorkUnits() {
        Date assignedDate;
        List<ClientDto> clients = clientService.getActive();
        for (ClientDto client : clients) {
            List<WorkUnitDto> workUnits = getWorkUnitsAssignedToClient(client.getClientId());

            if (workUnits.size() < workUnitCountPerClient) {
                assignedDate = new Date();
                List<WorkUnitDto> workUnitsToAssign = getUnassignedWorkUnits(workUnitCountPerClient - workUnits.size());
                for (WorkUnitDto workUnitToAssign : workUnitsToAssign) {
                    workUnitToAssign.setAssignedClient(client.getClientId());
                    workUnitToAssign.setAssignedDate(assignedDate);
                    workUnitToAssign.setStatus(WorkUnitStatus.ASSIGNED);
                }
                save(workUnitsToAssign);
                log.info("Work units assigned to client {}", client.getClientId());
            }
        }
    }


    public List<WorkUnitDto> getWorkUnitsAssignedToClient(String clientId) {
        log.info("Fetching work units assigned with client id {}", clientId);
        String uri = UriComponentsBuilder.fromHttpUrl(workUnitUri).queryParam("assignedClientId", clientId).queryParam("status", WorkUnitStatus.ASSIGNED).queryParam("pageSize", workUnitCountPerClient).toUriString();
        WorkUnitDto[] workUnits = restTemplate.getForObject(uri, WorkUnitDto[].class);
        if (isNull(workUnits)) {
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
        if (isNull(workUnits)) {
            log.info("No unassigned");
            return Collections.emptyList();
        }

        log.info("Found {} unassigned work units", workUnits.length);
        return Arrays.asList(workUnits);
    }

    public void save(List<WorkUnitDto> workUnitsToAssign) {
        restTemplate.postForObject(workUnitUrl, workUnitsToAssign, WorkUnitDto[].class);
    }

}

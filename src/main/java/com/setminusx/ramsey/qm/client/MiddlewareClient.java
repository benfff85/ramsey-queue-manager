package com.setminusx.ramsey.qm.client;

import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

@Service
public class MiddlewareClient {

    private final String clientUrl;
    private final String campaignUrl;
    private final String stageUrl;
    private final String workUnitUrl;
    private final String graphUrl;
    private final RestTemplate restTemplate;


    public MiddlewareClient(RamseyConfig ramseyConfig, RestTemplate restTemplate) {
        this.clientUrl = ramseyConfig.getClient().getUrl();
        this.campaignUrl = ramseyConfig.getCampaign().getUrl();
        this.stageUrl = ramseyConfig.getStage().getUrl();
        this.workUnitUrl = ramseyConfig.getWorkUnit().getQueue().getUrl();
        this.graphUrl = ramseyConfig.getGraph().getUrl();
        this.restTemplate = restTemplate;
    }

    ////////////////////////////////////////////////////////////////////////////////
    //                                Client                                   //
    ////////////////////////////////////////////////////////////////////////////////
    public Client createClient(Client client) {
        return restTemplate.postForObject(clientUrl, client, Client.class);
    }

    public void updateClient(Client client) {
        restTemplate.put(clientUrl + "/" + client.getClientId(), client);
    }

    public List<Client> getClientsByTypeAndStatusAndCampaign(ClientType type, ClientStatus status, Integer campaignId) {

        String getClientUri = UriComponentsBuilder.fromUriString(clientUrl)
                .queryParam("type", type)
                .queryParam("status", status)
                .queryParam("campaignId", campaignId)
                .toUriString();

        return Optional.ofNullable(restTemplate.getForObject(getClientUri, Client[].class))
                .map(Arrays::asList)
                .orElse(Collections.emptyList());
    }

    ////////////////////////////////////////////////////////////////////////////////
    //                                Campaign                                   //
    ////////////////////////////////////////////////////////////////////////////////
    public Campaign getCampaign(Integer campaignId) {
        return restTemplate.getForObject(campaignUrl + "/" + campaignId, Campaign.class);
    }

    ////////////////////////////////////////////////////////////////////////////////
    //                                Stage                                     //
    ////////////////////////////////////////////////////////////////////////////////
    public List<Stage> getStagesByCampaignIdAndStatus(Integer campaignId, Stage.Status status) {

        String getStageUri = UriComponentsBuilder.fromUriString(stageUrl)
                .queryParam("campaignId", campaignId)
                .queryParam("status", status)
                .toUriString();

        return Optional.ofNullable(restTemplate.getForObject(getStageUri, Stage[].class))
                .map(Arrays::asList)
                .orElse(Collections.emptyList());

    }

    public void updateStage(Stage stage) {
        restTemplate.put(stageUrl + "/" + stage.getStageId(), stage);
    }

    ////////////////////////////////////////////////////////////////////////////////
    //                                Work Unit                                   //
    ////////////////////////////////////////////////////////////////////////////////
    public List<WorkUnit> getWorkUnitsByStageIdAndStatus(Integer stageId, WorkUnitStatus status, Integer pageSize) {

        String getWorkUnitUri = UriComponentsBuilder.fromUriString(workUnitUrl)
                .queryParam("stageId", stageId)
                .queryParam("status", status)
                .queryParam("pageSize", pageSize)
                .toUriString();

        return Optional.ofNullable(restTemplate.getForObject(getWorkUnitUri, WorkUnit[].class))
                .map(Arrays::asList)
                .orElse(Collections.emptyList());

    }

    public List<WorkUnit> getWorkUnitsByAssignedClientAndStatus(String clientId, WorkUnitStatus status, Integer pageSize) {

        String getWorkUnitUri = UriComponentsBuilder.fromUriString(workUnitUrl)
                .queryParam("assignedClientId", clientId)
                .queryParam("status", status)
                .queryParam("pageSize", pageSize)
                .toUriString();

        return Optional.ofNullable(restTemplate.getForObject(getWorkUnitUri, WorkUnit[].class))
                .map(Arrays::asList)
                .orElse(Collections.emptyList());

    }

    public WorkUnit getWorkUnitById(Integer id) {
        return restTemplate.getForObject(workUnitUrl + "/" + id, WorkUnit.class);
    }

    public List<WorkUnit> createWorkUnits(List<WorkUnit> newWorkUnits) {
        return Optional.ofNullable(restTemplate.postForObject(workUnitUrl, newWorkUnits, WorkUnit[].class))
                .map(Arrays::asList)
                .orElse(Collections.emptyList());
    }

    public void updateWorkUnits(List<WorkUnit> workUnits) {
        restTemplate.put(workUnitUrl, workUnits);
    }

    ////////////////////////////////////////////////////////////////////////////////
    //                                Graph                                   //
    ////////////////////////////////////////////////////////////////////////////////
    public Graph getGraphById(Integer id) {
        return restTemplate.getForObject(graphUrl + "/" + id, Graph.class);
    }

}

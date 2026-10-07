package com.setminusx.ramsey.qm.client;

import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

@Service
public class MiddlewareClient {

    private final String campaignUrl;
    private final String stageUrl;
    private final String workUnitUrl;
    private final String graphUrl;
    private final RestTemplate restTemplate;

    public MiddlewareClient(RamseyConfig ramseyConfig, RestTemplate restTemplate) {
        this.campaignUrl = ramseyConfig.getCampaign().getUrl();
        this.stageUrl = ramseyConfig.getStage().getUrl();
        this.workUnitUrl = ramseyConfig.getWorkUnit().getQueue().getUrl();
        this.graphUrl = ramseyConfig.getGraph().getUrl();
        this.restTemplate = restTemplate;
    }

    ////////////////////////////////////////////////////////////////////////////////
    // Campaign //
    ////////////////////////////////////////////////////////////////////////////////
    public Campaign getCampaign(Integer campaignId) {
        return restTemplate.getForObject(campaignUrl + "/" + campaignId, Campaign.class);
    }

    /** Full stage history of a campaign (used by perturbation wall detection). */
    public List<ProgressionPoint> getProgression(Integer campaignId) {
        return Optional.ofNullable(
                        restTemplate.getForObject(campaignUrl + "/" + campaignId + "/progression",
                                ProgressionPoint[].class))
                .map(Arrays::asList)
                .orElse(Collections.emptyList());
    }

    ////////////////////////////////////////////////////////////////////////////////
    // Stage //
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

    /**
     * All ACTIVE stages across every campaign (campaignId omitted). The single
     * campaign-agnostic QM iterates these — see the fleet abstraction plan.
     */
    public List<Stage> getActiveStages() {
        String getStageUri = UriComponentsBuilder.fromUriString(stageUrl)
                .queryParam("status", Stage.Status.ACTIVE)
                .toUriString();

        return Optional.ofNullable(restTemplate.getForObject(getStageUri, Stage[].class))
                .map(Arrays::asList)
                .orElse(Collections.emptyList());
    }

    public List<Stage> getRecentStagesByCampaignIdAndStatus(Integer campaignId, Stage.Status status, int count) {

        String getStageUri = UriComponentsBuilder.fromUriString(stageUrl)
                .queryParam("campaignId", campaignId)
                .queryParam("status", status)
                .queryParam("count", count)
                .toUriString();

        return Optional.ofNullable(restTemplate.getForObject(getStageUri, Stage[].class))
                .map(Arrays::asList)
                .orElse(Collections.emptyList());

    }

    public void updateStage(Stage stage) {
        restTemplate.put(stageUrl + "/" + stage.getStageId(), stage);
    }

    ////////////////////////////////////////////////////////////////////////////////
    // Work Unit //
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
    // Graph //
    ////////////////////////////////////////////////////////////////////////////////
    public Graph getGraphById(Integer id) {
        return restTemplate.getForObject(graphUrl + "/" + id, Graph.class);
    }

    /**
     * Get a derived graph (computed but not saved).
     * 
     * @param baseGraphId The base graph to derive from
     * @param edgesToFlip String format "{{v1:v2},{v3:v4}}"
     * @return Derived graph (graphId will be null)
     */
    public Graph getDerivedGraph(Integer baseGraphId, String edgesToFlip) {
        // Use build().toUri() to properly encode, then pass URI to avoid
        // double-encoding
        java.net.URI uri = UriComponentsBuilder.fromUriString(graphUrl + "/" + baseGraphId)
                .queryParam("edgesToFlip", edgesToFlip)
                .build()
                .toUri();
        return restTemplate.getForObject(uri, Graph.class);
    }

    /**
     * Create/save a graph to the database.
     */
    public Graph createGraph(Graph graph) {
        return restTemplate.postForObject(graphUrl, graph, Graph.class);
    }

    /**
     * Create a new stage.
     */
    public Stage createStage(Stage stage) {
        return restTemplate.postForObject(stageUrl, stage, Stage.class);
    }

}

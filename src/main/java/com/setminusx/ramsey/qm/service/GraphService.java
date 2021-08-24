package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.dto.GraphDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;

import static java.util.Objects.isNull;

@Slf4j
@Component
public class GraphService {

    @Value("${ramsey.graph.url}")
    private String graphUrl;

    @Value("${ramsey.vertex-count}")
    private Integer vertexCount;

    @Value("${ramsey.subgraph-size}")
    private Integer subgraphSize;

    private final RestTemplate restTemplate;
    private String minGraphUri;

    public GraphService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    private void createUris() {
        minGraphUri = UriComponentsBuilder.fromHttpUrl(graphUrl)
                .queryParam("type", "min")
                .queryParam("vertexCount", vertexCount)
                .queryParam("subgraphSize", subgraphSize)
                .toUriString();
    }


    public GraphDto getMin() {
        log.info("Fetching minimum graph");


        GraphDto[] graphDtos = restTemplate.getForObject(minGraphUri, GraphDto[].class);
        if (isNull(graphDtos) || graphDtos.length == 0) {
            log.info("No graph");
            return null;
        }

        log.info("Min graph id {}", graphDtos[0].getGraphId());
        return graphDtos[0];
    }


}

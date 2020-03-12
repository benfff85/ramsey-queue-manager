package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.dto.ClientDto;
import com.setminusx.ramsey.qm.model.ClientStatus;
import com.setminusx.ramsey.qm.model.ClientType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.isNull;

@Slf4j
@Component
public class ClientService {

    @Value("${ramsey.client.registration.url}")
    private String url;

    @Value("${ramsey.vertex-count}")
    private Integer vertexCount;

    @Value("${ramsey.subgraph-size}")
    private Integer subgraphSize;

    private RestTemplate restTemplate;
    private String activeClientsUri;

    public ClientService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    private void createUris() {
        activeClientsUri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("subgraphSize", subgraphSize)
                .queryParam("vertexCount", vertexCount)
                .queryParam("clientType", ClientType.CLIQUECHECKER)
                .queryParam("clientStatus", ClientStatus.ACTIVE)
                .toUriString();
    }


    public List<ClientDto> getActive() {
        log.info("Fetching active clients");
        ClientDto[] clients = restTemplate.getForObject(activeClientsUri, ClientDto[].class);
        if (isNull(clients)) {
            log.info("No active clients");
            return Collections.emptyList();
        }

        log.info("Found {} active clients", clients.length);
        return Arrays.asList(clients);
    }
}

package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.dto.ClientDto;
import com.setminusx.ramsey.qm.model.ClientStatus;
import com.setminusx.ramsey.qm.model.ClientType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.isNull;

@Slf4j
@Component
public class ClientService {

    @Value("${ramsey.client.url}")
    private String baseUrl;

    @Value("${ramsey.vertex-count}")
    private Integer vertexCount;

    @Value("${ramsey.subgraph-size}")
    private Integer subgraphSize;

    private final RestTemplate restTemplate;
    private String clientsUri;

    public ClientService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    private void createUris() {
        clientsUri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("subgraphSize", subgraphSize)
                .queryParam("vertexCount", vertexCount)
                .toUriString();
    }


    public List<ClientDto> get(ClientType clientType, ClientStatus clientStatus) {
        log.info("Fetching clients");

        String url = clientsUri;
        if (!ClientType.ALL.equals(clientType)) {
            url = UriComponentsBuilder.fromHttpUrl(url).queryParam("clientType", clientType).toUriString();
        }
        if (!ClientStatus.ALL.equals(clientStatus)) {
            url = UriComponentsBuilder.fromHttpUrl(url).queryParam("clientStatus", clientStatus).toUriString();
        }

        ClientDto[] clients = restTemplate.getForObject(url, ClientDto[].class);
        if (isNull(clients) || clients.length == 0) {
            log.info("No clients");
            return Collections.emptyList();
        }

        log.info("Found {} clients", clients.length);
        return Arrays.asList(clients);
    }

    public void save(ClientDto clientDto) {
        restTemplate.postForObject(baseUrl, clientDto, ClientDto.class);
    }

}

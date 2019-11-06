package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.dto.ClientDto;
import com.setminusx.ramsey.qm.model.ClientType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class ClientRegister {

    @Value("${ramsey.client.registration.url}")
    private String url;

    @Value("${ramsey.vertex-count}")
    private Integer vertexCount;

    @Value("${ramsey.subgraph-size}")
    private Integer subgraphSize;

    private ClientDto client;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void register() {
        log.info("Registering client");
        HttpEntity<ClientDto> request = new HttpEntity<>(ClientDto.builder().vertexCount(vertexCount).subgraphSize(subgraphSize).type(ClientType.QUEUEMANAGER).build());
        ResponseEntity<ClientDto> response = restTemplate.exchange(url, HttpMethod.POST, request, ClientDto.class);
        client = response.getBody();
        log.info("Client registered with id: {}", client.getClientId());
    }

    @Scheduled(fixedRateString = "${ramsey.client.registration.phone-home.frequency-in-millis}")
    public void phoneHome() {
        log.debug("Phoning Home");
        restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(client), ClientDto.class);
    }

}

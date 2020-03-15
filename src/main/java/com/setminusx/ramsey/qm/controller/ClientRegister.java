package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.dto.ClientDto;
import com.setminusx.ramsey.qm.model.ClientType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;

import static com.setminusx.ramsey.qm.model.ClientStatus.ACTIVE;
import static java.time.LocalDateTime.now;

@Slf4j
@Component
public class ClientRegister {

    @Value("${ramsey.client.url}")
    private String url;

    @Value("${ramsey.vertex-count}")
    private Integer vertexCount;

    @Value("${ramsey.subgraph-size}")
    private Integer subgraphSize;

    private ClientDto client;
    private final RestTemplate restTemplate;

    public ClientRegister(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    public void register() {
        log.info("Creating client");
        client = ClientDto.builder()
                .vertexCount(vertexCount)
                .subgraphSize(subgraphSize)
                .type(ClientType.QUEUEMANAGER)
                .status(ACTIVE)
                .createdDate(now())
                .build();

        log.info("Client created");
    }

    @Scheduled(fixedRateString = "${ramsey.client.registration.phone-home.frequency-in-millis}")
    public void phoneHome() {
        log.debug("Phoning Home");
        client.setLastPhoneHomeDate(now());
        client = restTemplate.postForObject(url, client, ClientDto.class);
    }

}

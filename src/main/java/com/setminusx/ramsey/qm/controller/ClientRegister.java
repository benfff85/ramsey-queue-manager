package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.Campaign;
import com.setminusx.ramsey.qm.model.Client;
import com.setminusx.ramsey.qm.model.ClientType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.setminusx.ramsey.qm.model.ClientStatus.ACTIVE;
import static com.setminusx.ramsey.qm.utility.TimeUtility.now;

@Slf4j
@Component
public class ClientRegister {

    private final MiddlewareClient middlewareClient;
    private final RamseyConfig ramseyConfig;
    private Client client;

    public ClientRegister(MiddlewareClient middlewareClient, RamseyConfig ramseyConfig) {
        this.middlewareClient = middlewareClient;
        this.ramseyConfig = ramseyConfig;
    }

    @PostConstruct
    public void register() {

        // Pull campaign info and enrich the ramsey config
        Integer campaignId = ramseyConfig.getCampaignId();
        log.info("Pulling campaign info for campaign id {}", campaignId);
        Campaign campaign = middlewareClient.getCampaign(campaignId);
        log.info("Got campaign info: {}", campaign);

        ramseyConfig.setSubgraphSize(campaign.getSubgraphSize());
        ramseyConfig.setVertexCount(campaign.getVertexCount());

        client = Client.builder()
                .campaignId(campaignId)
                .type(ClientType.QUEUEMANAGER)
                .status(ACTIVE)
                .createdDate(now())
                .lastPhoneHomeDate(now())
                .build();

        log.info("Creating a new client: {}", client);
        client = middlewareClient.createClient(client);
        ramseyConfig.setClientId(client.getClientId());
        log.info("Client created: {}", client);
    }

    @Scheduled(fixedRateString = "${ramsey.client.registration.phone-home.frequency-in-millis}")
    public void phoneHome() {
        log.debug("Phoning Home");
        client.setLastPhoneHomeDate(now());
        middlewareClient.updateClient(client);
    }

}

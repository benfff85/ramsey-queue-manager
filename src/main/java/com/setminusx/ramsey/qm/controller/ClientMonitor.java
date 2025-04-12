package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.Client;
import com.setminusx.ramsey.qm.model.WorkUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static com.setminusx.ramsey.qm.model.ClientStatus.ACTIVE;
import static com.setminusx.ramsey.qm.model.ClientStatus.INACTIVE;
import static com.setminusx.ramsey.qm.model.ClientType.*;
import static com.setminusx.ramsey.qm.model.WorkUnitStatus.ASSIGNED;
import static com.setminusx.ramsey.qm.model.WorkUnitStatus.NEW;
import static com.setminusx.ramsey.qm.utility.TimeUtility.now;

@Slf4j
@Component
public class ClientMonitor {


    private final MiddlewareClient middlewareClient;
    private final RamseyConfig ramseyConfig;

    @Value("${ramsey.client.registration.timeout.threshold-in-minutes}")
    private Integer timeoutThreshold;


    public ClientMonitor(MiddlewareClient middlewareClient, RamseyConfig ramseyConfig) {
        this.middlewareClient = middlewareClient;
        this.ramseyConfig = ramseyConfig;
    }

    @Scheduled(fixedRateString = "${ramsey.client.registration.timeout.frequency-in-millis}")
    public void flagInactiveClients() {
        log.info("Checking for inactive clients");
        List<Client> activeWorkerClients = middlewareClient.getClientsByTypeAndStatusAndCampaign(CLIQUECHECKER, ACTIVE, ramseyConfig.getCampaignId());
        List<Client> activeQueueManagerClients = middlewareClient.getClientsByTypeAndStatusAndCampaign(QUEUEMANAGER, ACTIVE, ramseyConfig.getCampaignId());
        List<Client> activeClients = Stream.concat(activeWorkerClients.stream(), activeQueueManagerClients.stream()).toList();
        LocalDateTime now = now();
        long durationInMinutes;

        for (Client client : activeClients) {
            log.info("Checking if client {} is active", client.getClientId());
            durationInMinutes = Duration.between(client.getLastPhoneHomeDate(), now).toMinutes();
            log.info("Time since last phone home is {} minutes for client {}", durationInMinutes, client.getClientId());
            if (durationInMinutes > timeoutThreshold) {
                log.info("Threshold breached, flipping assigned work units back to NEW status for client {}", client.getClientId());
                List<WorkUnit> workUnits = middlewareClient.getWorkUnitsByAssignedClientAndStatus(client.getClientId(), ASSIGNED, ramseyConfig.getWorkUnit().getAssignment().getCountPerClient());
                if(!workUnits.isEmpty()) {
                    for (WorkUnit workUnit : workUnits) {
                        workUnit.setAssignedDate(null);
                        workUnit.setStatus(NEW);
                        workUnit.setAssignedClient(null);
                    }
                    middlewareClient.updateWorkUnits(workUnits);
                }

                log.info("Marking client {} as inactive", client.getClientId());
                client.setStatus(INACTIVE);
                middlewareClient.updateClient(client);
            }
            log.info("Completed inactivity check for client {}", client.getClientId());
        }
        log.info("Completed inactivity check for all clients");
    }

}

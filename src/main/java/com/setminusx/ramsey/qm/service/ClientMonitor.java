package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.dto.ClientDto;
import com.setminusx.ramsey.qm.dto.WorkUnitDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static com.setminusx.ramsey.qm.model.ClientStatus.ACTIVE;
import static com.setminusx.ramsey.qm.model.ClientStatus.INACTIVE;
import static com.setminusx.ramsey.qm.model.ClientType.ALL;
import static com.setminusx.ramsey.qm.model.WorkUnitStatus.NEW;
import static com.setminusx.ramsey.qm.utility.TimeUtility.now;

@Slf4j
@Component
public class ClientMonitor {

    @Value("${ramsey.client.registration.timeout.threshold-in-minutes}")
    private Integer timeoutThreshold;

    private final ClientService clientService;
    private final WorkUnitService workUnitService;


    public ClientMonitor(ClientService clientService, WorkUnitService workUnitService) {
        this.clientService = clientService;
        this.workUnitService = workUnitService;
    }

    @Scheduled(fixedRateString = "${ramsey.client.registration.timeout.frequency-in-millis}")
    public void flagInactiveClients() {
        log.info("Checking for inactive clients");
        List<ClientDto> activeClients = clientService.get(ALL, ACTIVE);
        LocalDateTime now = now();
        long durationInMinutes;

        for (ClientDto client : activeClients) {
            log.info("Checking if client {} is active", client.getClientId());
            durationInMinutes = Duration.between(client.getLastPhoneHomeDate(), now).toMinutes();
            log.info("Time since last phone home is {} minutes", durationInMinutes);
            if (durationInMinutes > timeoutThreshold) {
                log.info("Threshold breached, marking client as inactive");
                client.setStatus(INACTIVE);
                clientService.save(client);

                log.info("Flipping assigned work units back to NEW status");
                List<WorkUnitDto> workUnits = workUnitService.getWorkUnitsAssignedToClient(client.getClientId());
                for (WorkUnitDto workUnit : workUnits) {
                    workUnit.setAssignedDate(null);
                    workUnit.setStatus(NEW);
                    workUnit.setAssignedClient(null);
                }
                workUnitService.save(workUnits);
            }
        }
        log.info("Completed check for inactive clients");
    }

}

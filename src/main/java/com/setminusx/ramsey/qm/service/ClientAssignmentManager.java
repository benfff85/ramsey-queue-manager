package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.dto.ClientDto;
import com.setminusx.ramsey.qm.dto.WorkUnitDto;
import com.setminusx.ramsey.qm.model.WorkUnitStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static com.setminusx.ramsey.qm.model.ClientStatus.ACTIVE;
import static com.setminusx.ramsey.qm.model.ClientType.CLIQUECHECKER;
import static java.time.LocalDateTime.now;

@Slf4j
@Component
public class ClientAssignmentManager {


    @Value("${ramsey.work-unit.assignment.count-per-client}")
    private Integer workUnitCountPerClient;

    private ClientService clientService;
    private WorkUnitService workUnitService;


    public ClientAssignmentManager(ClientService clientService, WorkUnitService workUnitService) {
        this.clientService = clientService;
        this.workUnitService = workUnitService;
    }

    @Scheduled(fixedRateString = "${ramsey.work-unit.assignment.frequency-in-millis}")
    public void assignWorkUnits() {
        log.info("Starting assign work units");
        LocalDateTime assignedDate;
        List<ClientDto> clients = clientService.get(CLIQUECHECKER, ACTIVE);
        for (ClientDto client : clients) {
            List<WorkUnitDto> workUnits = workUnitService.getWorkUnitsAssignedToClient(client.getClientId());

            if (workUnits.size() < workUnitCountPerClient) {
                assignedDate = now();
                List<WorkUnitDto> workUnitsToAssign = workUnitService.getUnassignedWorkUnits(workUnitCountPerClient - workUnits.size());
                for (WorkUnitDto workUnitToAssign : workUnitsToAssign) {
                    workUnitToAssign.setAssignedClient(client.getClientId());
                    workUnitToAssign.setAssignedDate(assignedDate);
                    workUnitToAssign.setStatus(WorkUnitStatus.ASSIGNED);
                }
                workUnitService.save(workUnitsToAssign);
                log.info("Work units assigned to client {}", client.getClientId());
            }
        }
        log.info("Completed assignment of work units");
    }

}

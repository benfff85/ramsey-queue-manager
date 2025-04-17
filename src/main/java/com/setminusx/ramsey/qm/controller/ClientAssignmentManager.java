package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.Client;
import com.setminusx.ramsey.qm.model.Stage;
import com.setminusx.ramsey.qm.model.WorkUnit;
import com.setminusx.ramsey.qm.model.WorkUnitStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static com.setminusx.ramsey.qm.model.ClientStatus.ACTIVE;
import static com.setminusx.ramsey.qm.model.ClientType.CLIQUECHECKER;
import static com.setminusx.ramsey.qm.utility.TimeUtility.now;

@Slf4j
@Component
public class ClientAssignmentManager {

    private final RamseyConfig ramseyConfig;
    private final MiddlewareClient middlewareClient;


    public ClientAssignmentManager(RamseyConfig ramseyConfig, MiddlewareClient middlewareClient) {
        this.ramseyConfig = ramseyConfig;
        this.middlewareClient = middlewareClient;
    }

    @Scheduled(fixedRateString = "${ramsey.work-unit.assignment.frequency-in-millis}")
    public void assignWorkUnits() {
        log.info("Starting assign work units");
        Integer workUnitCountPerClient = ramseyConfig.getWorkUnit().getAssignment().getCountPerClient();
        LocalDateTime assignedDate;

        // Grab the active stage for the campaign
        List<Stage> stages = middlewareClient.getStagesByCampaignIdAndStatus(ramseyConfig.getCampaignId(), Stage.Status.ACTIVE);
        if (!(stages.size() == 1)) {
            throw new RuntimeException("Expected 1 active stage, found " + stages.size());
        }
        Stage stage = stages.getFirst();

        List<Client> clients = middlewareClient.getClientsByTypeAndStatusAndCampaign(CLIQUECHECKER, ACTIVE, ramseyConfig.getCampaignId());
        for (Client client : clients) {
            int workUnitCount = middlewareClient.getWorkUnitCountByClientIdAndStatus(client.getClientId(), WorkUnitStatus.ASSIGNED);
            if (workUnitCount < workUnitCountPerClient) {
                assignedDate = now();
                List<WorkUnit> workUnitsToAssign = middlewareClient.getWorkUnitsByStageIdAndStatus(stage.getStageId(), WorkUnitStatus.NEW, workUnitCountPerClient - workUnitCount);
                for (WorkUnit workUnitToAssign : workUnitsToAssign) {
                    workUnitToAssign.setAssignedClient(client.getClientId());
                    workUnitToAssign.setAssignedDate(assignedDate);
                    workUnitToAssign.setStatus(WorkUnitStatus.ASSIGNED);
                }
                middlewareClient.updateWorkUnits(workUnitsToAssign);
                log.info("Client {} assigned {} work units", client.getClientId(), workUnitsToAssign.size());
            }
        }
        log.info("Completed assignment of work units");
    }

}

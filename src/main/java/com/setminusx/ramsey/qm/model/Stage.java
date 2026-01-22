package com.setminusx.ramsey.qm.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Stage {

    private Integer stageId;
    private Stage.Status status;
    private Integer baseGraphId;
    private Integer campaignId;
    private Integer latestWorkUnitId;
    private String workEnumerationStrategy;
    private String details;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

    public enum Status {
        ACTIVE,
        INACTIVE
    }

}

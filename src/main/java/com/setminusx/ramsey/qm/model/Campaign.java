package com.setminusx.ramsey.qm.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Campaign {

    private Integer campaignId;
    private Integer subgraphSize;
    private Integer vertexCount;
    private Campaign.Strategy strategy;
    private Campaign.Status status;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

    public enum Status {
        ACTIVE,
        INACTIVE
    }

    public enum Strategy {
        COMPREHENSIVE_EDGE_PAIR_MUTATION
    }

}

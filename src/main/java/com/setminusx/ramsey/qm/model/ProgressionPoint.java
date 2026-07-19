package com.setminusx.ramsey.qm.model;

import lombok.Data;

/** One stage of a campaign's history, as returned by GET /campaigns/{id}/progression. */
@Data
public class ProgressionPoint {
    private Integer stageId;
    private Integer graphId; // the stage's base graph id (mw ProgressionDTO serializes this as "graphId")
    private Long cliqueCount;
    private String createdDate;
    private String status;
}

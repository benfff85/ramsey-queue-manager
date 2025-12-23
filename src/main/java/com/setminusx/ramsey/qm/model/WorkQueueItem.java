package com.setminusx.ramsey.qm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lightweight work queue item for Redis storage.
 * Note: stageId not included as worker gets it from MW API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkQueueItem {

    private Integer baseGraphId;
    private List<Edge> edgesToFlip;
    private WorkUnitAnalysisType analysisType;

}

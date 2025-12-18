package com.setminusx.ramsey.qm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lightweight work queue item for Redis storage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkQueueItem {

    private Integer baseGraphId;
    private Integer stageId;
    private List<Edge> edgesToFlip;
    private WorkUnitAnalysisType analysisType;

}

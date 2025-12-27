package com.setminusx.ramsey.qm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lightweight work queue item for Redis storage.
 * Note: stageId and analysisType not included - worker gets from MW API/config
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkQueueItem {

    private Integer baseGraphId;
    private List<Edge> edgesToFlip;

}

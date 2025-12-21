package com.setminusx.ramsey.qm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Best result for a stage - tracked in Redis for stage progression.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BestResult {

    private Integer baseGraphId;
    private Integer stageId;
    private List<Edge> edgesToFlip;
    private Integer cliqueCount;

}

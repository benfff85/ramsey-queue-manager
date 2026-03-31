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
    private String graphBitstring;

    /**
     * Returns true if this result came from a simulated annealing worker
     * (identified by having a graphBitstring instead of edgesToFlip).
     */
    public boolean isSimulatedAnnealingResult() {
        return graphBitstring != null && !graphBitstring.isBlank();
    }

}

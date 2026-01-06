package com.setminusx.ramsey.qm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Represents a pair of edges (one red, one blue) for a work unit.
 * Includes combined cardinality score for prioritized ordering.
 */
@Data
@Builder
@AllArgsConstructor
public class EdgePair {
    private Edge redEdge;
    private Edge blueEdge;
    private Integer combinedScore;
}

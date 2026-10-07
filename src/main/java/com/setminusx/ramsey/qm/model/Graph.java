package com.setminusx.ramsey.qm.model;

import lombok.Data;

import java.util.Date;

@Data
public class Graph {

    private Integer graphId;
    private Integer subgraphSize;
    private Integer vertexCount;
    private String edgeData;
    /** Lineage (ramsey-mw graph-delta-lineage): parent graph, flips from it, hash, hops to the nearest snapshot. */
    private Integer parentGraphId;
    private String flippedEdges;
    private String graphHash;
    private Integer lineageDepth;
    private Integer cliqueCount;
    private Date identifiedDate;

}

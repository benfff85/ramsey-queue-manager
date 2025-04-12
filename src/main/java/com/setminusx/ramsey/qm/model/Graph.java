package com.setminusx.ramsey.qm.model;

import lombok.Data;

import java.util.Date;

@Data
public class Graph {

    private Integer graphId;
    private Integer subgraphSize;
    private Integer vertexCount;
    private String edgeData;
    private Integer cliqueCount;
    private Date identifiedDate;

}

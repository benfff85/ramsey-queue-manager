package com.setminusx.ramsey.qm.dto;

import lombok.Data;

import java.util.Date;

@Data
public class GraphDto {

    private Integer graphId;
    private Integer subgraphSize;
    private Integer vertexCount;
    private String edgeData;
    private Integer cliqueCount;
    private Date identifiedDate;

}

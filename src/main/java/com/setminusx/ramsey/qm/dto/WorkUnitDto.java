package com.setminusx.ramsey.qm.dto;

import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.WorkUnitPriority;
import com.setminusx.ramsey.qm.model.WorkUnitStatus;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class WorkUnitDto {

    private Integer id;
    private Integer subgraphSize;
    private Integer vertexCount;
    private Integer baseGraphId;
    private List<Edge> edgesToFlip;
    private WorkUnitStatus status;
    private Integer cliqueCount;
    private Date createdDate;
    private Date assignedDate;
    private Date processingStartedDate;
    private Date completedDate;
    private String assignedClient;
    private WorkUnitPriority priority;

}

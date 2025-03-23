package com.setminusx.ramsey.qm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkUnit {

    private Integer id;
    private Integer subgraphSize;
    private Integer vertexCount;
    private Integer baseGraphId;
    private Integer stageId;
    private List<Edge> edgesToFlip;
    private WorkUnitStatus status;
    private Integer cliqueCount;
    private LocalDateTime createdDate;
    private LocalDateTime assignedDate;
    private LocalDateTime processingStartedDate;
    private LocalDateTime completedDate;
    private String assignedClient;
    private WorkUnitPriority priority;
    private WorkUnitAnalysisType workUnitAnalysisType;

}

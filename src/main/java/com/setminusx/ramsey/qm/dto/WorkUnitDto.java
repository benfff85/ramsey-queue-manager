package com.setminusx.ramsey.qm.dto;

import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.WorkUnitAnalysisType;
import com.setminusx.ramsey.qm.model.WorkUnitPriority;
import com.setminusx.ramsey.qm.model.WorkUnitStatus;
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
public class WorkUnitDto {

    private Integer id;
    private Integer subgraphSize;
    private Integer vertexCount;
    private Integer baseGraphId;
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

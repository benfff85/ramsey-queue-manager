package com.setminusx.ramsey.qm.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Edge {

    private Integer vertexOne;
    private Integer vertexTwo;

    @JsonIgnore
    @EqualsAndHashCode.Exclude
    private Character coloring;

    @JsonIgnore
    @EqualsAndHashCode.Exclude
    private Integer cardinality;

}

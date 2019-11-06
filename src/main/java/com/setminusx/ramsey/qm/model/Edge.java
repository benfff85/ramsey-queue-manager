package com.setminusx.ramsey.qm.model;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Edge {

    private Integer vertexOne;
    private Integer vertexTwo;

    @EqualsAndHashCode.Exclude
    private char coloring;

}

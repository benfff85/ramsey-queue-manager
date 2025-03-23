package com.setminusx.ramsey.qm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Client {

    private String clientId;
    private Integer subgraphSize;
    private Integer vertexCount;
    private Integer campaignId;
    private ClientType type;
    private ClientStatus status;
    private LocalDateTime createdDate;
    private LocalDateTime lastPhoneHomeDate;

}

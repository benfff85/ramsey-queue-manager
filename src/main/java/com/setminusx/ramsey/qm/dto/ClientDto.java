package com.setminusx.ramsey.qm.dto;

import com.setminusx.ramsey.qm.model.ClientStatus;
import com.setminusx.ramsey.qm.model.ClientType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientDto {

    private String clientId;
    private Integer subgraphSize;
    private Integer vertexCount;
    private ClientType type;
    private ClientStatus status;
    private Date createdDate;
    private Date lastPhoneHomeDate;

}

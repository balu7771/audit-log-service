package com.persistent.auditlog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RedactionResponse {

    @JsonProperty("sequenceId")
    private Long sequenceId;

    @JsonProperty("fieldPath")
    private String fieldPath;

    @JsonProperty("alreadyRedacted")
    private boolean alreadyRedacted;

    @JsonProperty("redactionAuditEventSequenceId")
    private Long redactionAuditEventSequenceId;
}

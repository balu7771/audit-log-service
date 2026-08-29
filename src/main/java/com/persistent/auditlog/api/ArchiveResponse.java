package com.persistent.auditlog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArchiveResponse {

    @JsonProperty("archivedCount")
    private int archivedCount;

    @JsonProperty("cutoffTimestamp")
    private Instant cutoffTimestamp;

    @JsonProperty("windowDaysUsed")
    private int windowDaysUsed;
}

package com.persistent.auditlog.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VerificationResponse {

    @JsonProperty("intact")
    private boolean intact;

    @JsonProperty("totalRecords")
    private long totalRecords;

    @JsonProperty("lastVerifiedSequenceId")
    private Long lastVerifiedSequenceId;

    @JsonProperty("verifiedRecordsCount")
    private int verifiedRecordsCount;

    @JsonProperty("archivedRecordsCount")
    private long archivedRecordsCount;

    @JsonProperty("violation")
    private ViolationDetail violation;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ViolationDetail {
        @JsonProperty("sequenceId")
        private Long sequenceId;

        @JsonProperty("violationType")
        private String violationType;

        @JsonProperty("expectedValue")
        private String expectedValue;

        @JsonProperty("actualValue")
        private String actualValue;

        @JsonProperty("details")
        private String details;
    }
}

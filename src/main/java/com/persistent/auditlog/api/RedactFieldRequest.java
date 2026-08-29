package com.persistent.auditlog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedactFieldRequest {

    @NotBlank(message = "fieldPath is required")
    @JsonProperty("fieldPath")
    private String fieldPath;

    @JsonProperty("actorId")
    private String actorId;

    @JsonProperty("reason")
    private String reason;
}

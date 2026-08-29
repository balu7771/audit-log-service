package com.persistent.auditlog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateAuditEventRequest {

    @NotBlank(message = "eventType is required")
    @JsonProperty("eventType")
    private String eventType;

    @NotBlank(message = "actorId is required")
    @JsonProperty("actorId")
    private String actorId;

    @NotBlank(message = "resourceType is required")
    @JsonProperty("resourceType")
    private String resourceType;

    @NotBlank(message = "resourceId is required")
    @JsonProperty("resourceId")
    private String resourceId;

    @NotBlank(message = "payload is required")
    @JsonProperty("payload")
    private String payload;

    @JsonProperty("clientTimestamp")
    private Instant clientTimestamp;

    // Optional top-level payload field names to encrypt at write time (crypto-
    // shredding redaction scheme). Only fields declared here can later be
    // redacted; nested field paths are not supported - a top-level field's
    // entire value is encrypted as one unit.
    @JsonProperty("sensitiveFields")
    private List<String> sensitiveFields;
}

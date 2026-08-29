package com.persistent.auditlog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.persistent.auditlog.domain.AuditEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEventResponse {

    @JsonProperty("sequenceId")
    private Long sequenceId;

    @JsonProperty("eventType")
    private String eventType;

    @JsonProperty("actorId")
    private String actorId;

    @JsonProperty("resourceType")
    private String resourceType;

    @JsonProperty("resourceId")
    private String resourceId;

    @JsonProperty("payload")
    private String payload;

    @JsonProperty("serverTimestamp")
    private Instant serverTimestamp;

    @JsonProperty("clientTimestamp")
    private Instant clientTimestamp;

    @JsonProperty("contentHash")
    private String contentHash;

    @JsonProperty("recordHash")
    private String recordHash;

    @JsonProperty("previousHash")
    private String previousHash;

    @JsonProperty("archivedAt")
    private Instant archivedAt;

    @JsonProperty("sensitiveFields")
    private List<String> sensitiveFields;

    public static AuditEventResponse fromEntity(AuditEvent entity) {
        return fromEntity(entity, entity.getPayload(), null);
    }

    /**
     * renderedPayload lets callers substitute a decrypted/placeholder payload
     * (see PayloadRedactionService) without exposing this DTO to a service
     * dependency; sensitiveFields is the record's originally-declared list.
     */
    public static AuditEventResponse fromEntity(AuditEvent entity, String renderedPayload, List<String> sensitiveFields) {
        return AuditEventResponse.builder()
            .sequenceId(entity.getSequenceId())
            .eventType(entity.getEventType())
            .actorId(entity.getActorId())
            .resourceType(entity.getResourceType())
            .resourceId(entity.getResourceId())
            .payload(renderedPayload)
            .serverTimestamp(entity.getServerTimestamp())
            .clientTimestamp(entity.getClientTimestamp())
            .contentHash(entity.getContentHash())
            .recordHash(entity.getRecordHash())
            .previousHash(entity.getPreviousHash())
            .archivedAt(entity.getArchivedAt())
            .sensitiveFields(sensitiveFields)
            .build();
    }
}

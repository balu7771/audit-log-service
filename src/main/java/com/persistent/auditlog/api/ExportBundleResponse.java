package com.persistent.auditlog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
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
public class ExportBundleResponse {

    @JsonProperty("exportedAt")
    private Instant exportedAt;

    @JsonProperty("actorId")
    private String actorId;

    @JsonProperty("resourceType")
    private String resourceType;

    @JsonProperty("resourceId")
    private String resourceId;

    @JsonProperty("recordCount")
    private int recordCount;

    @JsonProperty("records")
    private List<ExportedRecord> records;

    @JsonProperty("manifest")
    private ExportManifest manifest;

    @JsonProperty("signature")
    private ExportSignature signature;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExportedRecord {
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

        // Display form: sensitive fields decrypted (key present) or "[REDACTED]" (key destroyed).
        @JsonProperty("payload")
        private String payload;

        // Verbatim stored form (ciphertext-bearing where applicable) - this, not
        // "payload", is what contentHash actually covers and must be used to
        // recompute/verify hashes.
        @JsonProperty("storedPayload")
        private String storedPayload;

        @JsonProperty("serverTimestamp")
        private Instant serverTimestamp;

        @JsonProperty("contentHash")
        private String contentHash;

        @JsonProperty("recordHash")
        private String recordHash;

        @JsonProperty("previousHash")
        private String previousHash;

        @JsonProperty("archivedAt")
        private Instant archivedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExportManifest {
        @JsonProperty("algorithm")
        private String algorithm;

        @JsonProperty("manifestHash")
        private String manifestHash;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExportSignature {
        @JsonProperty("algorithm")
        private String algorithm;

        @JsonProperty("value")
        private String value;
    }
}

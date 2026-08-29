package com.persistent.auditlog.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AuditEventHasher {

    private static final String GENESIS_PREVIOUS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    private final ObjectMapper objectMapper;

    public AuditEventHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void computeHash(AuditEvent event, AuditEvent previousEvent) {
        // serverTimestamp must be fixed before hashing - it is otherwise assigned later by
        // @PrePersist, which would make the stored contentHash unreproducible on verification.
        // Truncated to microseconds to match Postgres TIMESTAMP column precision, since a
        // nanosecond-resolution Instant would be rounded on persist and no longer reproduce
        // the same hash on verification.
        if (event.getServerTimestamp() == null) {
            event.setServerTimestamp(Instant.now().truncatedTo(ChronoUnit.MICROS));
        } else {
            event.setServerTimestamp(event.getServerTimestamp().truncatedTo(ChronoUnit.MICROS));
        }

        // Compute contentHash over canonical event data
        String contentHash = computeContentHash(event);
        event.setContentHash(contentHash);

        // Set previousHash from prior event or genesis value
        String previousHash = previousEvent != null
            ? previousEvent.getRecordHash()
            : GENESIS_PREVIOUS_HASH;
        event.setPreviousHash(previousHash);

        // Compute recordHash from contentHash + previousHash
        String recordHash = computeRecordHash(contentHash, previousHash);
        event.setRecordHash(recordHash);
    }

    /**
     * Recomputes contentHash for an already-persisted event from a detached
     * copy of its own fields, without mutating the original or requiring a
     * previous event. Shared by chain verification and export-bundle
     * verification so both use identical hashing logic.
     */
    public String recomputeContentHashOnly(AuditEvent event) {
        AuditEvent detached = AuditEvent.builder()
            .eventType(event.getEventType())
            .actorId(event.getActorId())
            .resourceType(event.getResourceType())
            .resourceId(event.getResourceId())
            .payload(event.getPayload())
            .serverTimestamp(event.getServerTimestamp())
            .build();
        computeHash(detached, null);
        return detached.getContentHash();
    }

    private String computeContentHash(AuditEvent event) {
        // Create canonical JSON in fixed field order (excluding clientTimestamp, hashes, sequenceId)
        Map<String, Object> canonicalData = new LinkedHashMap<>();
        canonicalData.put("eventType", event.getEventType());
        canonicalData.put("actorId", event.getActorId());
        canonicalData.put("resourceType", event.getResourceType());
        canonicalData.put("resourceId", event.getResourceId());
        canonicalData.put("payload", parseJsonIfString(event.getPayload()));
        canonicalData.put("serverTimestamp", event.getServerTimestamp());

        String canonical = toCanonicalJson(canonicalData);
        return sha256Hex(canonical);
    }

    private String computeRecordHash(String contentHash, String previousHash) {
        String input = contentHash + previousHash;
        return sha256Hex(input);
    }

    private Object parseJsonIfString(String payload) {
        try {
            return objectMapper.readValue(payload, Object.class);
        } catch (Exception e) {
            // Return as string if not valid JSON
            return payload;
        }
    }

    private String toCanonicalJson(Map<String, Object> data) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize to canonical JSON", e);
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}

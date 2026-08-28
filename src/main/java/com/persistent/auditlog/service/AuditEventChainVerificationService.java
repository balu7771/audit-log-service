package com.persistent.auditlog.service;

import com.persistent.auditlog.api.VerificationResponse;
import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.domain.AuditEventHasher;
import com.persistent.auditlog.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuditEventChainVerificationService {

    private final AuditEventRepository auditEventRepository;
    private final AuditEventHasher auditEventHasher;
    private final AuditEventFailureLogger failureLogger;

    @Transactional
    public VerificationResponse verifyChain(Optional<Integer> lastN) {
        List<AuditEvent> allEvents = auditEventRepository.findAll(Sort.by(Sort.Direction.ASC, "sequenceId"));
        long totalRecords = allEvents.size();

        if (allEvents.isEmpty()) {
            return VerificationResponse.builder()
                .intact(true)
                .totalRecords(0)
                .lastVerifiedSequenceId(null)
                .verifiedRecordsCount(0)
                .build();
        }

        // Determine which events to verify
        List<AuditEvent> eventsToVerify = allEvents;
        int startIndex = 0;
        if (lastN.isPresent() && lastN.get() < allEvents.size()) {
            startIndex = allEvents.size() - lastN.get();
            eventsToVerify = allEvents.subList(startIndex, allEvents.size());
        }

        // Verify chain
        for (int i = 0; i < eventsToVerify.size(); i++) {
            AuditEvent event = eventsToVerify.get(i);
            int originalIndex = startIndex + i;

            // Check sequence continuity
            if (i == 0 && lastN.isEmpty()) {
                // First event should have sequence_id = 1
                if (event.getSequenceId() != 1) {
                    VerificationResponse.ViolationDetail violation = VerificationResponse.ViolationDetail.builder()
                        .sequenceId(event.getSequenceId())
                        .violationType("SEQUENCE_GAP")
                        .details("Expected sequence_id 1, but found " + event.getSequenceId())
                        .build();
                    VerificationResponse response = VerificationResponse.builder()
                        .intact(false)
                        .totalRecords(totalRecords)
                        .lastVerifiedSequenceId(originalIndex > 0 ? allEvents.get(originalIndex - 1).getSequenceId() : null)
                        .verifiedRecordsCount(i)
                        .violation(violation)
                        .build();
                    logViolation(violation);
                    return response;
                }
            } else if (i > 0) {
                // Check sequence continuity
                AuditEvent prevEvent = eventsToVerify.get(i - 1);
                if (event.getSequenceId() != prevEvent.getSequenceId() + 1) {
                    VerificationResponse.ViolationDetail violation = VerificationResponse.ViolationDetail.builder()
                        .sequenceId(event.getSequenceId())
                        .violationType("SEQUENCE_GAP")
                        .details("Expected sequence_id " + (prevEvent.getSequenceId() + 1) + ", but found " + event.getSequenceId())
                        .build();
                    VerificationResponse response = VerificationResponse.builder()
                        .intact(false)
                        .totalRecords(totalRecords)
                        .lastVerifiedSequenceId(prevEvent.getSequenceId())
                        .verifiedRecordsCount(i)
                        .violation(violation)
                        .build();
                    logViolation(violation);
                    return response;
                }
            }

            // Verify contentHash by recomputing
            String computedContentHash = computeContentHash(event);

            if (!computedContentHash.equals(event.getContentHash())) {
                VerificationResponse.ViolationDetail violation = VerificationResponse.ViolationDetail.builder()
                    .sequenceId(event.getSequenceId())
                    .violationType("CONTENT_HASH_MISMATCH")
                    .expectedValue(computedContentHash)
                    .actualValue(event.getContentHash())
                    .details("Payload or other content was modified")
                    .build();
                VerificationResponse response = VerificationResponse.builder()
                    .intact(false)
                    .totalRecords(totalRecords)
                    .lastVerifiedSequenceId(originalIndex > 0 ? allEvents.get(originalIndex - 1).getSequenceId() : null)
                    .verifiedRecordsCount(i)
                    .violation(violation)
                    .build();
                logViolation(violation);
                return response;
            }

            // Verify recordHash
            String expectedPreviousHash = i == 0 && lastN.isEmpty()
                ? "0000000000000000000000000000000000000000000000000000000000000000"
                : eventsToVerify.get(i - 1).getRecordHash();

            String computedRecordHash = computeRecordHash(event.getContentHash(), expectedPreviousHash);
            if (!computedRecordHash.equals(event.getRecordHash())) {
                VerificationResponse.ViolationDetail violation = VerificationResponse.ViolationDetail.builder()
                    .sequenceId(event.getSequenceId())
                    .violationType("RECORD_HASH_MISMATCH")
                    .expectedValue(computedRecordHash)
                    .actualValue(event.getRecordHash())
                    .details("Record hash does not match expected value")
                    .build();
                VerificationResponse response = VerificationResponse.builder()
                    .intact(false)
                    .totalRecords(totalRecords)
                    .lastVerifiedSequenceId(originalIndex > 0 ? allEvents.get(originalIndex - 1).getSequenceId() : null)
                    .verifiedRecordsCount(i)
                    .violation(violation)
                    .build();
                logViolation(violation);
                return response;
            }

            // Verify previousHash
            if (!expectedPreviousHash.equals(event.getPreviousHash())) {
                VerificationResponse.ViolationDetail violation = VerificationResponse.ViolationDetail.builder()
                    .sequenceId(event.getSequenceId())
                    .violationType("PREVIOUS_HASH_MISMATCH")
                    .expectedValue(expectedPreviousHash)
                    .actualValue(event.getPreviousHash())
                    .details("Previous hash does not link to prior record")
                    .build();
                VerificationResponse response = VerificationResponse.builder()
                    .intact(false)
                    .totalRecords(totalRecords)
                    .lastVerifiedSequenceId(originalIndex > 0 ? allEvents.get(originalIndex - 1).getSequenceId() : null)
                    .verifiedRecordsCount(i)
                    .violation(violation)
                    .build();
                logViolation(violation);
                return response;
            }
        }

        // Chain is intact
        return VerificationResponse.builder()
            .intact(true)
            .totalRecords(totalRecords)
            .lastVerifiedSequenceId(allEvents.get(allEvents.size() - 1).getSequenceId())
            .verifiedRecordsCount(eventsToVerify.size())
            .build();
    }

    private String computeRecordHash(String contentHash, String previousHash) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((contentHash + previousHash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String computeContentHash(AuditEvent event) {
        // Use the hasher to compute - create a temp event and compute its hash
        AuditEvent tempEvent = AuditEvent.builder()
            .eventType(event.getEventType())
            .actorId(event.getActorId())
            .resourceType(event.getResourceType())
            .resourceId(event.getResourceId())
            .payload(event.getPayload())
            .serverTimestamp(event.getServerTimestamp())
            .build();

        // Compute hash without linking (pass null as previous)
        auditEventHasher.computeHash(tempEvent, null);

        return tempEvent.getContentHash();
    }

    private String computeSha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void logViolation(VerificationResponse.ViolationDetail violation) {
        failureLogger.logValidationFailure("CHAIN_VIOLATION",
            "Chain violation detected: " + violation.getViolationType() + " at sequence " + violation.getSequenceId(),
            violation);
    }
}

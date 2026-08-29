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

    private static final String GENESIS_PREVIOUS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

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

        int startIndex = 0;
        if (lastN.isPresent() && lastN.get() < allEvents.size()) {
            startIndex = allEvents.size() - lastN.get();
        }
        List<AuditEvent> eventsToVerify = allEvents.subList(startIndex, allEvents.size());
        long archivedRecordsCount = eventsToVerify.stream().filter(e -> e.getArchivedAt() != null).count();

        for (int i = 0; i < eventsToVerify.size(); i++) {
            AuditEvent event = eventsToVerify.get(i);
            int originalIndex = startIndex + i;
            AuditEvent priorEvent = originalIndex > 0 ? allEvents.get(originalIndex - 1) : null;

            // Sequence continuity - archived records are still part of the primary
            // sequence and are walked exactly like non-archived ones; archiving
            // never removes a row or changes its sequence_id, so this never
            // false-positives on legitimately archived records.
            long expectedSequenceId = priorEvent != null ? priorEvent.getSequenceId() + 1 : 1L;
            if (event.getSequenceId() != expectedSequenceId) {
                return recordViolation(totalRecords, priorEvent, i, archivedRecordsCount, VerificationResponse.ViolationDetail.builder()
                    .sequenceId(event.getSequenceId())
                    .violationType("SEQUENCE_GAP")
                    .details("Expected sequence_id " + expectedSequenceId + ", but found " + event.getSequenceId())
                    .build());
            }

            // contentHash - recomputed from the stored payload/fields
            String computedContentHash = auditEventHasher.recomputeContentHashOnly(event);
            if (!computedContentHash.equals(event.getContentHash())) {
                return recordViolation(totalRecords, priorEvent, i, archivedRecordsCount, VerificationResponse.ViolationDetail.builder()
                    .sequenceId(event.getSequenceId())
                    .violationType("CONTENT_HASH_MISMATCH")
                    .expectedValue(computedContentHash)
                    .actualValue(event.getContentHash())
                    .details("Payload or other content was modified")
                    .build());
            }

            // recordHash = SHA-256(contentHash + previousHash)
            String expectedPreviousHash = priorEvent != null ? priorEvent.getRecordHash() : GENESIS_PREVIOUS_HASH;
            String computedRecordHash = computeRecordHash(event.getContentHash(), expectedPreviousHash);
            if (!computedRecordHash.equals(event.getRecordHash())) {
                return recordViolation(totalRecords, priorEvent, i, archivedRecordsCount, VerificationResponse.ViolationDetail.builder()
                    .sequenceId(event.getSequenceId())
                    .violationType("RECORD_HASH_MISMATCH")
                    .expectedValue(computedRecordHash)
                    .actualValue(event.getRecordHash())
                    .details("Record hash does not match expected value")
                    .build());
            }

            // previousHash link to prior record
            if (!expectedPreviousHash.equals(event.getPreviousHash())) {
                return recordViolation(totalRecords, priorEvent, i, archivedRecordsCount, VerificationResponse.ViolationDetail.builder()
                    .sequenceId(event.getSequenceId())
                    .violationType("PREVIOUS_HASH_MISMATCH")
                    .expectedValue(expectedPreviousHash)
                    .actualValue(event.getPreviousHash())
                    .details("Previous hash does not link to prior record")
                    .build());
            }
        }

        return VerificationResponse.builder()
            .intact(true)
            .totalRecords(totalRecords)
            .lastVerifiedSequenceId(allEvents.get(allEvents.size() - 1).getSequenceId())
            .verifiedRecordsCount(eventsToVerify.size())
            .archivedRecordsCount(archivedRecordsCount)
            .build();
    }

    private VerificationResponse recordViolation(long totalRecords, AuditEvent priorEvent, int verifiedCount,
                                                  long archivedRecordsCount,
                                                  VerificationResponse.ViolationDetail violation) {
        logViolation(violation);
        return VerificationResponse.builder()
            .intact(false)
            .totalRecords(totalRecords)
            .lastVerifiedSequenceId(priorEvent != null ? priorEvent.getSequenceId() : null)
            .verifiedRecordsCount(verifiedCount)
            .archivedRecordsCount(archivedRecordsCount)
            .violation(violation)
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

    private void logViolation(VerificationResponse.ViolationDetail violation) {
        failureLogger.logValidationFailure("CHAIN_VIOLATION",
            "Chain violation detected: " + violation.getViolationType() + " at sequence " + violation.getSequenceId(),
            violation);
    }
}

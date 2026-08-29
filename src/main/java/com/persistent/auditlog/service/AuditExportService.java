package com.persistent.auditlog.service;

import com.persistent.auditlog.api.ExportBundleResponse;
import com.persistent.auditlog.api.ExportVerificationResponse;
import com.persistent.auditlog.api.AuditEventResponse;
import com.persistent.auditlog.api.VerificationResponse;
import com.persistent.auditlog.crypto.HmacSigner;
import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.domain.AuditEventHasher;
import com.persistent.auditlog.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Bulk export as a self-contained, independently verifiable bundle. Each
 * record carries its own hash fields (per-record integrity), plus a
 * bundle-level manifest hash over all included recordHashes (catches
 * add/remove/reorder tampering) and an HMAC signature over that manifest
 * (catches wholesale bundle fabrication). See ASSUMPTIONS-AND-TRADEOFFS.md for
 * the provenance limitations of this scheme.
 */
@Service
public class AuditExportService {

    private final AuditEventRepository auditEventRepository;
    private final AuditEventHasher auditEventHasher;
    private final PayloadRedactionService payloadRedactionService;
    private final HmacSigner hmacSigner;
    private final int maxRecords;

    public AuditExportService(AuditEventRepository auditEventRepository,
                               AuditEventHasher auditEventHasher,
                               PayloadRedactionService payloadRedactionService,
                               HmacSigner hmacSigner,
                               @Value("${audit.export.max-records}") int maxRecords) {
        this.auditEventRepository = auditEventRepository;
        this.auditEventHasher = auditEventHasher;
        this.payloadRedactionService = payloadRedactionService;
        this.hmacSigner = hmacSigner;
        this.maxRecords = maxRecords;
    }

    @Transactional(readOnly = true)
    public ExportBundleResponse exportBundle(String actorId, String resourceType, String resourceId) {
        validateFilter(actorId, resourceType, resourceId);

        Specification<AuditEvent> spec = buildSpecification(actorId, resourceType, resourceId);

        long matchCount = auditEventRepository.count(spec);
        if (matchCount > maxRecords) {
            throw new IllegalArgumentException(
                "Export would include " + matchCount + " records, exceeding the max of " + maxRecords + "; narrow the filter");
        }

        List<AuditEvent> events = auditEventRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "sequenceId"));
        List<AuditEventResponse> rendered = payloadRedactionService.renderPage(events);

        List<ExportBundleResponse.ExportedRecord> records = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            AuditEvent event = events.get(i);
            AuditEventResponse renderedRecord = rendered.get(i);
            records.add(ExportBundleResponse.ExportedRecord.builder()
                .sequenceId(event.getSequenceId())
                .eventType(event.getEventType())
                .actorId(event.getActorId())
                .resourceType(event.getResourceType())
                .resourceId(event.getResourceId())
                .payload(renderedRecord.getPayload())
                .storedPayload(event.getPayload())
                .serverTimestamp(event.getServerTimestamp())
                .contentHash(event.getContentHash())
                .recordHash(event.getRecordHash())
                .previousHash(event.getPreviousHash())
                .archivedAt(event.getArchivedAt())
                .build());
        }

        String manifestHash = computeManifestHash(recordHashesOf(records));
        String signature = hmacSigner.sign(manifestHash);

        return ExportBundleResponse.builder()
            .exportedAt(Instant.now())
            .actorId(actorId)
            .resourceType(resourceType)
            .resourceId(resourceId)
            .recordCount(records.size())
            .records(records)
            .manifest(ExportBundleResponse.ExportManifest.builder()
                .algorithm("SHA-256")
                .manifestHash(manifestHash)
                .build())
            .signature(ExportBundleResponse.ExportSignature.builder()
                .algorithm("HmacSHA256")
                .value(signature)
                .build())
            .build();
    }

    public ExportVerificationResponse verifyBundle(ExportBundleResponse bundle) {
        List<ExportBundleResponse.ExportedRecord> records = bundle.getRecords() != null ? bundle.getRecords() : List.of();

        VerificationResponse.ViolationDetail firstViolation = null;
        boolean perRecordIntact = true;
        String previousRecordHash = null;

        for (int i = 0; i < records.size(); i++) {
            ExportBundleResponse.ExportedRecord record = records.get(i);

            AuditEvent temp = AuditEvent.builder()
                .eventType(record.getEventType())
                .actorId(record.getActorId())
                .resourceType(record.getResourceType())
                .resourceId(record.getResourceId())
                .payload(record.getStoredPayload())
                .serverTimestamp(record.getServerTimestamp())
                .build();
            String computedContentHash = auditEventHasher.recomputeContentHashOnly(temp);

            if (!computedContentHash.equals(record.getContentHash())) {
                perRecordIntact = false;
                firstViolation = VerificationResponse.ViolationDetail.builder()
                    .sequenceId(record.getSequenceId())
                    .violationType("CONTENT_HASH_MISMATCH")
                    .expectedValue(computedContentHash)
                    .actualValue(record.getContentHash())
                    .details("Recomputed content hash does not match bundle record")
                    .build();
                break;
            }

            String computedRecordHash = sha256Hex(record.getContentHash() + record.getPreviousHash());
            if (!computedRecordHash.equals(record.getRecordHash())) {
                perRecordIntact = false;
                firstViolation = VerificationResponse.ViolationDetail.builder()
                    .sequenceId(record.getSequenceId())
                    .violationType("RECORD_HASH_MISMATCH")
                    .expectedValue(computedRecordHash)
                    .actualValue(record.getRecordHash())
                    .details("Recomputed record hash does not match bundle record")
                    .build();
                break;
            }

            // Only checks linkage between records that are actually present in
            // this (possibly sparse) bundle - the first record's previousHash
            // cannot be verified from the bundle alone. See docs for this
            // documented provenance limitation.
            if (i > 0 && !record.getPreviousHash().equals(previousRecordHash)) {
                perRecordIntact = false;
                firstViolation = VerificationResponse.ViolationDetail.builder()
                    .sequenceId(record.getSequenceId())
                    .violationType("PREVIOUS_HASH_MISMATCH")
                    .expectedValue(previousRecordHash)
                    .actualValue(record.getPreviousHash())
                    .details("Record does not link to the prior record in this bundle")
                    .build();
                break;
            }

            previousRecordHash = record.getRecordHash();
        }

        String recomputedManifestHash = computeManifestHash(recordHashesOf(records));
        boolean manifestIntact = bundle.getManifest() != null && recomputedManifestHash.equals(bundle.getManifest().getManifestHash());

        boolean signatureIntact = bundle.getSignature() != null && bundle.getManifest() != null
            && hmacSigner.verify(bundle.getManifest().getManifestHash(), bundle.getSignature().getValue());

        boolean valid = perRecordIntact && manifestIntact && signatureIntact;

        return ExportVerificationResponse.builder()
            .valid(valid)
            .recordCount(records.size())
            .perRecordIntact(perRecordIntact)
            .manifestIntact(manifestIntact)
            .signatureIntact(signatureIntact)
            .firstViolation(firstViolation)
            .build();
    }

    private void validateFilter(String actorId, String resourceType, String resourceId) {
        boolean hasActor = actorId != null && !actorId.isBlank();
        boolean hasResource = resourceType != null && !resourceType.isBlank();

        if (resourceId != null && !hasResource) {
            throw new IllegalArgumentException("resourceId requires resourceType to be specified");
        }
        if (hasActor && hasResource) {
            throw new IllegalArgumentException("Specify either actorId or resourceType(+resourceId), not both");
        }
        if (!hasActor && !hasResource) {
            throw new IllegalArgumentException("Must specify either actorId or resourceType(+resourceId)");
        }
    }

    private Specification<AuditEvent> buildSpecification(String actorId, String resourceType, String resourceId) {
        Specification<AuditEvent> spec = Specification.where(null);
        if (actorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("actorId"), actorId));
        }
        if (resourceType != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("resourceType"), resourceType));
        }
        if (resourceId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("resourceId"), resourceId));
        }
        return spec;
    }

    private List<String> recordHashesOf(List<ExportBundleResponse.ExportedRecord> records) {
        return records.stream().map(ExportBundleResponse.ExportedRecord::getRecordHash).collect(Collectors.toList());
    }

    private String computeManifestHash(List<String> recordHashesInOrder) {
        return sha256Hex(String.join("", recordHashesInOrder));
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}

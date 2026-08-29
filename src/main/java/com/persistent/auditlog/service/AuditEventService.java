package com.persistent.auditlog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.persistent.auditlog.api.CreateAuditEventRequest;
import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.domain.AuditEventHasher;
import com.persistent.auditlog.domain.RedactionKey;
import com.persistent.auditlog.domain.RedactionKeyId;
import com.persistent.auditlog.repository.AuditEventRepository;
import com.persistent.auditlog.repository.RedactionKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditEventRepository auditEventRepository;
    private final AuditEventHasher auditEventHasher;
    private final PayloadRedactionService payloadRedactionService;
    private final RedactionKeyRepository redactionKeyRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public AuditEvent createAuditEvent(CreateAuditEventRequest request) {
        String payload = request.getPayload();
        String sensitiveFieldsJson = null;
        PayloadRedactionService.EncryptOutcome outcome = null;

        // Sensitive fields are encrypted BEFORE hashing, so contentHash always
        // covers ciphertext - redacting a field later never needs to change
        // the stored hash, and AuditEventHasher itself needs no awareness of
        // redaction at all.
        if (request.getSensitiveFields() != null && !request.getSensitiveFields().isEmpty()) {
            outcome = payloadRedactionService.encryptSensitiveFields(payload, request.getSensitiveFields());
            payload = outcome.transformedPayload();
            sensitiveFieldsJson = toJson(request.getSensitiveFields());
        }

        AuditEvent newEvent = AuditEvent.builder()
            .eventType(request.getEventType())
            .actorId(request.getActorId())
            .resourceType(request.getResourceType())
            .resourceId(request.getResourceId())
            .payload(payload)
            .clientTimestamp(request.getClientTimestamp())
            .sensitiveFields(sensitiveFieldsJson)
            .build();

        // Fetch the last event to link the chain
        AuditEvent previousEvent = findLastEvent();

        // Compute hashes
        auditEventHasher.computeHash(newEvent, previousEvent);

        // Persist
        AuditEvent savedEvent = auditEventRepository.save(newEvent);

        // Persist per-field decryption keys in the same transaction as the
        // audit_events insert, so a failure here rolls back the whole write -
        // there is never a ciphertext-without-key orphan.
        if (outcome != null) {
            List<RedactionKey> keys = new ArrayList<>();
            for (PayloadRedactionService.FieldKeyMaterial material : outcome.keyMaterial()) {
                keys.add(RedactionKey.builder()
                    .id(new RedactionKeyId(savedEvent.getSequenceId(), material.fieldPath()))
                    .encryptionKey(material.key())
                    .iv(material.iv())
                    .build());
            }
            redactionKeyRepository.saveAll(keys);
        }

        return savedEvent;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize sensitiveFields", e);
        }
    }

    private AuditEvent findLastEvent() {
        return auditEventRepository.findAll(Sort.by(Sort.Direction.DESC, "sequenceId"))
            .stream()
            .findFirst()
            .orElse(null);
    }
}

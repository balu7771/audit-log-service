package com.persistent.auditlog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.persistent.auditlog.api.CreateAuditEventRequest;
import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.repository.AuditEventRepository;
import com.persistent.auditlog.repository.RedactionKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Performs redaction by destroying a field's decryption key - never by
 * mutating audit_events. The redaction action itself is recorded as a normal,
 * chain-protected FIELD_REDACTED audit event (mirrors AuditEventFailureLogger's
 * self-logging pattern), giving tamper-evidence over the redaction itself.
 */
@Service
@RequiredArgsConstructor
public class RedactionService {

    private final AuditEventRepository auditEventRepository;
    private final RedactionKeyRepository redactionKeyRepository;
    private final PayloadRedactionService payloadRedactionService;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    public record RedactionResult(Long sequenceId, String fieldPath, boolean alreadyRedacted,
                                   Long redactionAuditEventSequenceId) {
    }

    @Transactional
    public RedactionResult redactField(Long sequenceId, String fieldPath, String actorId, String reason) {
        AuditEvent event = auditEventRepository.findBySequenceId(sequenceId)
            .orElseThrow(() -> new IllegalArgumentException("No audit event with sequenceId " + sequenceId));

        List<String> sensitiveFields = payloadRedactionService.parseSensitiveFields(event.getSensitiveFields());
        if (sensitiveFields == null || !sensitiveFields.contains(fieldPath)) {
            throw new IllegalArgumentException(
                "Field '" + fieldPath + "' was not declared sensitive at creation for sequenceId " + sequenceId);
        }

        // Idempotent: redacting an already-redacted field is a no-op success,
        // not an error - concurrent redaction requests are naturally safe
        // since a second delete-by-key is simply a no-op.
        boolean keyExisted = redactionKeyRepository.findByIdSequenceIdAndIdFieldPath(sequenceId, fieldPath).isPresent();
        if (!keyExisted) {
            return new RedactionResult(sequenceId, fieldPath, true, null);
        }

        redactionKeyRepository.deleteByIdSequenceIdAndIdFieldPath(sequenceId, fieldPath);

        Map<String, Object> redactionPayload = new HashMap<>();
        redactionPayload.put("redactedSequenceId", sequenceId);
        redactionPayload.put("fieldPath", fieldPath);
        redactionPayload.put("reason", reason);

        CreateAuditEventRequest redactionEvent = new CreateAuditEventRequest();
        redactionEvent.setEventType("FIELD_REDACTED");
        redactionEvent.setActorId(actorId != null ? actorId : "system");
        redactionEvent.setResourceType(event.getResourceType());
        redactionEvent.setResourceId(event.getResourceId());
        redactionEvent.setPayload(toJson(redactionPayload));

        AuditEvent saved = auditEventService.createAuditEvent(redactionEvent);

        return new RedactionResult(sequenceId, fieldPath, false, saved.getSequenceId());
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}

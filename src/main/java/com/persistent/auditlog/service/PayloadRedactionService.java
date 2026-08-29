package com.persistent.auditlog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.persistent.auditlog.api.AuditEventResponse;
import com.persistent.auditlog.crypto.FieldEncryptor;
import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.domain.RedactionKey;
import com.persistent.auditlog.repository.RedactionKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implements the crypto-shredding redaction scheme: sensitive payload fields
 * are encrypted before the hash chain sees them (so the chain never needs to
 * change), and redaction is later performed by destroying the field's key in
 * a separate table, never by touching audit_events. This service also owns
 * the read-path: rendering ciphertext back to plaintext (key present) or a
 * "[REDACTED]" placeholder (key destroyed) for API consumers.
 */
@Service
@RequiredArgsConstructor
public class PayloadRedactionService {

    private static final String REDACTED_PLACEHOLDER = "[REDACTED]";

    private final FieldEncryptor fieldEncryptor;
    private final RedactionKeyRepository redactionKeyRepository;
    private final ObjectMapper objectMapper;

    public record EncryptOutcome(String transformedPayload, List<FieldKeyMaterial> keyMaterial) {
    }

    public record FieldKeyMaterial(String fieldPath, byte[] key, byte[] iv) {
    }

    /**
     * Encrypts each declared top-level sensitive field in place within the
     * payload, replacing its value with a ciphertext envelope. Must be called
     * BEFORE AuditEventHasher.computeHash so the stored contentHash covers
     * ciphertext from the very first write.
     */
    public EncryptOutcome encryptSensitiveFields(String payloadJson, List<String> sensitiveFieldNames) {
        JsonNode root = parsePayload(payloadJson);
        if (!root.isObject()) {
            throw new IllegalArgumentException("sensitiveFields requires payload to be a JSON object");
        }
        ObjectNode objectNode = (ObjectNode) root;
        List<FieldKeyMaterial> keyMaterial = new ArrayList<>();

        for (String fieldName : sensitiveFieldNames) {
            JsonNode fieldValue = objectNode.get(fieldName);
            if (fieldValue == null) {
                throw new IllegalArgumentException("sensitiveFields references unknown payload field: " + fieldName);
            }

            String plaintext = writeJson(fieldValue);
            FieldEncryptor.EncryptedField encrypted = fieldEncryptor.encrypt(plaintext);

            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("__enc", true);
            envelope.put("alg", "AES-256-GCM");
            envelope.put("iv", Base64.getEncoder().encodeToString(encrypted.iv()));
            envelope.put("ciphertext", encrypted.ciphertextBase64());
            objectNode.set(fieldName, envelope);

            keyMaterial.add(new FieldKeyMaterial(fieldName, encrypted.key(), encrypted.iv()));
        }

        return new EncryptOutcome(writeJson(objectNode), keyMaterial);
    }

    public List<String> parseSensitiveFields(String sensitiveFieldsJson) {
        if (sensitiveFieldsJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(sensitiveFieldsJson,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Renders a single event's payload for API consumption (decrypt-or-placeholder). */
    public AuditEventResponse renderForRead(AuditEvent entity) {
        return renderPage(List.of(entity)).get(0);
    }

    /** Batch variant avoiding an N+1 redaction_keys lookup per record on a page. */
    public List<AuditEventResponse> renderPage(List<AuditEvent> entities) {
        List<Long> sequenceIdsWithSensitiveFields = entities.stream()
            .filter(e -> e.getSensitiveFields() != null)
            .map(AuditEvent::getSequenceId)
            .collect(Collectors.toList());

        Map<Long, Map<String, RedactionKey>> keysBySequenceId = sequenceIdsWithSensitiveFields.isEmpty()
            ? Map.of()
            : redactionKeyRepository.findByIdSequenceIdIn(sequenceIdsWithSensitiveFields).stream()
                .collect(Collectors.groupingBy(k -> k.getId().getSequenceId(),
                    Collectors.toMap(k -> k.getId().getFieldPath(), k -> k)));

        List<AuditEventResponse> responses = new ArrayList<>();
        for (AuditEvent entity : entities) {
            List<String> sensitiveFields = parseSensitiveFields(entity.getSensitiveFields());
            if (sensitiveFields == null || sensitiveFields.isEmpty()) {
                responses.add(AuditEventResponse.fromEntity(entity, entity.getPayload(), sensitiveFields));
                continue;
            }
            Map<String, RedactionKey> keysByField = keysBySequenceId.getOrDefault(entity.getSequenceId(), Map.of());
            String renderedPayload = renderPayload(entity.getPayload(), sensitiveFields, keysByField);
            responses.add(AuditEventResponse.fromEntity(entity, renderedPayload, sensitiveFields));
        }
        return responses;
    }

    private String renderPayload(String storedPayload, List<String> sensitiveFields, Map<String, RedactionKey> keysByField) {
        JsonNode root = parsePayload(storedPayload);
        if (!root.isObject()) {
            return storedPayload;
        }
        ObjectNode objectNode = (ObjectNode) root;

        for (String fieldName : sensitiveFields) {
            RedactionKey key = keysByField.get(fieldName);
            if (key == null) {
                objectNode.set(fieldName, TextNode.valueOf(REDACTED_PLACEHOLDER));
                continue;
            }

            JsonNode envelope = objectNode.get(fieldName);
            if (envelope == null || !envelope.has("ciphertext")) {
                continue; // defensive: unexpected shape, leave untouched
            }

            byte[] iv = Base64.getDecoder().decode(envelope.get("iv").asText());
            String plaintextJson = fieldEncryptor.decrypt(key.getEncryptionKey(), iv, envelope.get("ciphertext").asText());
            try {
                objectNode.set(fieldName, objectMapper.readTree(plaintextJson));
            } catch (Exception e) {
                objectNode.set(fieldName, TextNode.valueOf(plaintextJson));
            }
        }

        return writeJson(objectNode);
    }

    private JsonNode parsePayload(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("payload is not valid JSON");
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }
}

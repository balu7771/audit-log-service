package com.persistent.auditlog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.domain.AuditEventHasher;
import com.persistent.auditlog.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditEventFailureLogger {

    private final AuditEventRepository auditEventRepository;
    private final AuditEventHasher auditEventHasher;
    private final ObjectMapper objectMapper;

    @Transactional
    public void logValidationFailure(String errorCode, String errorMessage, Object originalRequest) {
        Map<String, Object> failurePayload = new HashMap<>();
        failurePayload.put("errorCode", errorCode);
        failurePayload.put("errorMessage", errorMessage);
        if (originalRequest != null) {
            failurePayload.put("originalRequest", originalRequest);
        }
        failurePayload.put("timestamp", System.currentTimeMillis());

        String eventType = "CHAIN_VIOLATION".equals(errorCode) ? "AUDIT_CHAIN_VIOLATION" : "AUDIT_EVENT_WRITE_FAILURE";

        AuditEvent failureEvent = AuditEvent.builder()
            .eventType(eventType)
            .actorId("system")
            .resourceType("AUDIT_CONTROL")
            .resourceId("verification-result")
            .payload(toJson(failurePayload))
            .build();

        AuditEvent previousEvent = findLastEvent();
        auditEventHasher.computeHash(failureEvent, previousEvent);

        auditEventRepository.save(failureEvent);
    }

    private AuditEvent findLastEvent() {
        return auditEventRepository.findAll(Sort.by(Sort.Direction.DESC, "sequenceId"))
            .stream()
            .findFirst()
            .orElse(null);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}

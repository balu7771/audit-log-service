package com.persistent.auditlog.service;

import com.persistent.auditlog.api.CreateAuditEventRequest;
import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.domain.AuditEventHasher;
import com.persistent.auditlog.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditEventRepository auditEventRepository;
    private final AuditEventHasher auditEventHasher;

    @Transactional
    public AuditEvent createAuditEvent(CreateAuditEventRequest request) {
        AuditEvent newEvent = AuditEvent.builder()
            .eventType(request.getEventType())
            .actorId(request.getActorId())
            .resourceType(request.getResourceType())
            .resourceId(request.getResourceId())
            .payload(request.getPayload())
            .clientTimestamp(request.getClientTimestamp())
            .build();

        // Fetch the last event to link the chain
        AuditEvent previousEvent = findLastEvent();

        // Compute hashes
        auditEventHasher.computeHash(newEvent, previousEvent);

        // Persist
        return auditEventRepository.save(newEvent);
    }

    private AuditEvent findLastEvent() {
        return auditEventRepository.findAll(Sort.by(Sort.Direction.DESC, "sequenceId"))
            .stream()
            .findFirst()
            .orElse(null);
    }
}

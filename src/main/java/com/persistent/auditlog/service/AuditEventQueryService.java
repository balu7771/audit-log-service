package com.persistent.auditlog.service;

import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuditEventQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private final AuditEventRepository auditEventRepository;

    public Page<AuditEvent> queryAuditEvents(String actorId, String resourceType, String resourceId,
                                             String eventType, Instant from, Instant to,
                                             int page, int size) {
        validateQueryParams(resourceType, resourceId, size);

        Specification<AuditEvent> spec = buildSpecification(actorId, resourceType, resourceId, eventType, from, to);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sequenceId"));

        return auditEventRepository.findAll(spec, pageable);
    }

    private void validateQueryParams(String resourceType, String resourceId, int size) {
        if (resourceId != null && resourceType == null) {
            throw new IllegalArgumentException("resourceId requires resourceType to be specified");
        }
        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
    }

    private Specification<AuditEvent> buildSpecification(String actorId, String resourceType, String resourceId,
                                                        String eventType, Instant from, Instant to) {
        Specification<AuditEvent> spec = Specification.where(actorIdSpec(actorId))
            .and(resourceTypeSpec(resourceType))
            .and(resourceIdSpec(resourceId))
            .and(eventTypeSpec(eventType))
            .and(serverTimestampFromSpec(from))
            .and(serverTimestampToSpec(to));
        return spec;
    }

    private Specification<AuditEvent> actorIdSpec(String actorId) {
        return (root, query, cb) -> actorId == null ? null : cb.equal(root.get("actorId"), actorId);
    }

    private Specification<AuditEvent> resourceTypeSpec(String resourceType) {
        return (root, query, cb) -> resourceType == null ? null : cb.equal(root.get("resourceType"), resourceType);
    }

    private Specification<AuditEvent> resourceIdSpec(String resourceId) {
        return (root, query, cb) -> resourceId == null ? null : cb.equal(root.get("resourceId"), resourceId);
    }

    private Specification<AuditEvent> eventTypeSpec(String eventType) {
        return (root, query, cb) -> eventType == null ? null : cb.equal(root.get("eventType"), eventType);
    }

    private Specification<AuditEvent> serverTimestampFromSpec(Instant from) {
        return (root, query, cb) -> from == null ? null : cb.greaterThanOrEqualTo(root.get("serverTimestamp"), from);
    }

    private Specification<AuditEvent> serverTimestampToSpec(Instant to) {
        return (root, query, cb) -> to == null ? null : cb.lessThanOrEqualTo(root.get("serverTimestamp"), to);
    }
}

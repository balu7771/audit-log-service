package com.persistent.auditlog.service;

import com.persistent.auditlog.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class AuditEventRetentionService {

    private final AuditEventRepository auditEventRepository;
    private final int defaultWindowDays;

    public AuditEventRetentionService(AuditEventRepository auditEventRepository,
                                       @Value("${audit.retention.window-days}") int defaultWindowDays) {
        this.auditEventRepository = auditEventRepository;
        this.defaultWindowDays = defaultWindowDays;
    }

    public record ArchiveResult(int archivedCount, Instant cutoff, int windowDaysUsed) {
    }

    @Transactional
    public ArchiveResult archiveEligibleRecords(Optional<Integer> windowDaysOverride) {
        int windowDays = windowDaysOverride.orElse(defaultWindowDays);
        Instant cutoff = Instant.now().minus(Duration.ofDays(windowDays));
        Instant archivedAt = Instant.now();
        int archivedCount = auditEventRepository.archiveEligible(archivedAt, cutoff);
        return new ArchiveResult(archivedCount, cutoff, windowDays);
    }
}

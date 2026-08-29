package com.persistent.auditlog.repository;

import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.domain.AuditEventHasher;
import com.persistent.auditlog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private AuditEventHasher auditEventHasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_events RESTART IDENTITY CASCADE");
    }

    @Test
    void testPersistAndRetrieveAuditEventWithHashChain() {
        // Create and hash event 1 (genesis)
        AuditEvent event1 = AuditEvent.builder()
            .eventType("USER_LOGIN")
            .actorId("user-123")
            .resourceType("USER")
            .resourceId("user-123")
            .payload("{\"ip\": \"192.168.1.1\"}")
            .build();

        auditEventHasher.computeHash(event1, null);
        AuditEvent saved1 = auditEventRepository.save(event1);

        assertThat(saved1.getSequenceId()).isNotNull();
        assertThat(saved1.getSequenceId()).isEqualTo(1);

        // Create and hash event 2 (linked to event 1)
        AuditEvent event2 = AuditEvent.builder()
            .eventType("DATA_READ")
            .actorId("user-123")
            .resourceType("DOCUMENT")
            .resourceId("doc-456")
            .payload("{\"document_id\": \"doc-456\"}")
            .build();

        auditEventHasher.computeHash(event2, saved1);
        AuditEvent saved2 = auditEventRepository.save(event2);

        assertThat(saved2.getSequenceId()).isNotNull();
        assertThat(saved2.getSequenceId()).isEqualTo(2);
        assertThat(saved2.getPreviousHash()).isEqualTo(saved1.getRecordHash());

        // Retrieve and verify chain
        AuditEvent retrieved1 = auditEventRepository.findBySequenceId(1L).orElseThrow();
        AuditEvent retrieved2 = auditEventRepository.findBySequenceId(2L).orElseThrow();

        assertThat(retrieved2.getPreviousHash()).isEqualTo(retrieved1.getRecordHash());
        assertThat(retrieved2.getContentHash()).isNotNull();
        assertThat(retrieved2.getRecordHash()).isNotNull();
    }

    @Test
    void testSequenceIdAutoIncrementsCorrectly() {
        AuditEvent event1 = AuditEvent.builder()
            .eventType("EVENT_1")
            .actorId("actor")
            .resourceType("RESOURCE")
            .resourceId("res")
            .payload("{}")
            .build();
        auditEventHasher.computeHash(event1, null);
        AuditEvent saved1 = auditEventRepository.save(event1);

        AuditEvent event2 = AuditEvent.builder()
            .eventType("EVENT_2")
            .actorId("actor")
            .resourceType("RESOURCE")
            .resourceId("res")
            .payload("{}")
            .build();
        auditEventHasher.computeHash(event2, saved1);
        AuditEvent saved2 = auditEventRepository.save(event2);

        AuditEvent event3 = AuditEvent.builder()
            .eventType("EVENT_3")
            .actorId("actor")
            .resourceType("RESOURCE")
            .resourceId("res")
            .payload("{}")
            .build();
        auditEventHasher.computeHash(event3, saved2);
        AuditEvent saved3 = auditEventRepository.save(event3);

        assertThat(saved1.getSequenceId()).isEqualTo(1);
        assertThat(saved2.getSequenceId()).isEqualTo(2);
        assertThat(saved3.getSequenceId()).isEqualTo(3);
    }

    @Test
    void testBothContentHashAndRecordHashArePersisted() {
        AuditEvent event = AuditEvent.builder()
            .eventType("USER_LOGIN")
            .actorId("user-1")
            .resourceType("USER")
            .resourceId("user-1")
            .payload("{\"details\": \"test\"}")
            .build();

        auditEventHasher.computeHash(event, null);
        AuditEvent saved = auditEventRepository.save(event);

        AuditEvent retrieved = auditEventRepository.findBySequenceId(1L).orElseThrow();

        assertThat(retrieved.getContentHash()).isNotNull().isNotEmpty();
        assertThat(retrieved.getRecordHash()).isNotNull().isNotEmpty();
        assertThat(retrieved.getContentHash()).hasSize(64);
        assertThat(retrieved.getRecordHash()).hasSize(64);
    }
}

package com.persistent.auditlog.repository;

import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.domain.AuditEventHasher;
import com.persistent.auditlog.domain.RedactionKey;
import com.persistent.auditlog.domain.RedactionKeyId;
import com.persistent.auditlog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedactionKeyRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private RedactionKeyRepository redactionKeyRepository;

    @Autowired
    private AuditEventHasher auditEventHasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sequenceId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_events RESTART IDENTITY CASCADE");

        AuditEvent event = AuditEvent.builder()
            .eventType("USER_UPDATED")
            .actorId("actor-1")
            .resourceType("USER")
            .resourceId("user-1")
            .payload("{}")
            .build();
        auditEventHasher.computeHash(event, null);
        sequenceId = auditEventRepository.save(event).getSequenceId();
    }

    @Test
    void savesAndFindsKeysByCompositeId() {
        RedactionKey key = RedactionKey.builder()
            .id(new RedactionKeyId(sequenceId, "ssn"))
            .encryptionKey(new byte[]{1, 2, 3})
            .iv(new byte[]{4, 5, 6})
            .createdAt(Instant.now())
            .build();
        redactionKeyRepository.save(key);

        assertThat(redactionKeyRepository.findByIdSequenceId(sequenceId)).hasSize(1);
        assertThat(redactionKeyRepository.findByIdSequenceIdAndIdFieldPath(sequenceId, "ssn")).isPresent();
        assertThat(redactionKeyRepository.findByIdSequenceIdIn(List.of(sequenceId))).hasSize(1);
    }

    @Test
    @Transactional
    void deleteByCompositeIdRemovesOnlyThatField() {
        redactionKeyRepository.save(RedactionKey.builder()
            .id(new RedactionKeyId(sequenceId, "ssn"))
            .encryptionKey(new byte[]{1})
            .iv(new byte[]{2})
            .build());
        redactionKeyRepository.save(RedactionKey.builder()
            .id(new RedactionKeyId(sequenceId, "email"))
            .encryptionKey(new byte[]{3})
            .iv(new byte[]{4})
            .build());

        redactionKeyRepository.deleteByIdSequenceIdAndIdFieldPath(sequenceId, "ssn");

        assertThat(redactionKeyRepository.findByIdSequenceIdAndIdFieldPath(sequenceId, "ssn")).isEmpty();
        assertThat(redactionKeyRepository.findByIdSequenceIdAndIdFieldPath(sequenceId, "email")).isPresent();
    }
}

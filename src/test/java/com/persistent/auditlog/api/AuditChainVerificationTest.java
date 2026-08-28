package com.persistent.auditlog.api;

import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.domain.AuditEventHasher;
import com.persistent.auditlog.repository.AuditEventRepository;
import com.persistent.auditlog.support.AbstractIntegrationTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditChainVerificationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private AuditEventHasher auditEventHasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbcTemplate.execute("TRUNCATE TABLE audit_events RESTART IDENTITY");
    }

    private void disableTrigger() {
        jdbcTemplate.execute("ALTER TABLE audit_events DISABLE TRIGGER audit_events_immutable_trigger");
    }

    private void enableTrigger() {
        jdbcTemplate.execute("ALTER TABLE audit_events ENABLE TRIGGER audit_events_immutable_trigger");
    }

    @Test
    void testVerifyIntactChainReturnsTrue() throws Exception {
        // Create valid chain of 3 events
        createValidChain(3);

        mockMvc.perform(get("/audit/events/verify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(true))
            .andExpect(jsonPath("$.totalRecords").value(3))
            .andExpect(jsonPath("$.lastVerifiedSequenceId").value(3))
            .andExpect(jsonPath("$.verifiedRecordsCount").value(3));
    }

    @Test
    void testVerifyEmptyChainReturnsTrue() throws Exception {
        mockMvc.perform(get("/audit/events/verify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(true))
            .andExpect(jsonPath("$.totalRecords").value(0));
    }

    @Test
    void testVerifyDetectsContentHashMismatch() throws Exception {
        // Create valid chain
        createValidChain(3);

        // Corrupt payload of record 2 (disable trigger temporarily)
        disableTrigger();
        jdbcTemplate.execute("UPDATE audit_events SET payload = '{\"corrupted\": true}' WHERE sequence_id = 2");
        enableTrigger();

        mockMvc.perform(get("/audit/events/verify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(false))
            .andExpect(jsonPath("$.violation.sequenceId").value(2))
            .andExpect(jsonPath("$.violation.violationType").value("CONTENT_HASH_MISMATCH"));
    }

    @Test
    void testVerifyDetectsRecordHashMismatch() throws Exception {
        // Create valid chain
        createValidChain(3);

        // Corrupt recordHash of record 2
        disableTrigger();
        jdbcTemplate.execute("UPDATE audit_events SET record_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' WHERE sequence_id = 2");
        enableTrigger();

        mockMvc.perform(get("/audit/events/verify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(false))
            .andExpect(jsonPath("$.violation.sequenceId").value(2))
            .andExpect(jsonPath("$.violation.violationType").value("RECORD_HASH_MISMATCH"));
    }

    @Test
    void testVerifyDetectsPreviousHashMismatch() throws Exception {
        // Create valid chain
        createValidChain(3);

        // Corrupt previousHash of record 3 (break link)
        disableTrigger();
        jdbcTemplate.execute("UPDATE audit_events SET previous_hash = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' WHERE sequence_id = 3");
        enableTrigger();

        mockMvc.perform(get("/audit/events/verify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(false))
            .andExpect(jsonPath("$.violation.sequenceId").value(3))
            .andExpect(jsonPath("$.violation.violationType").value("PREVIOUS_HASH_MISMATCH"));
    }

    @Test
    void testVerifyDetectsSequenceGap() throws Exception {
        // Create valid chain of 3, then manually delete record 2
        createValidChain(3);
        disableTrigger();
        jdbcTemplate.execute("DELETE FROM audit_events WHERE sequence_id = 2");
        enableTrigger();

        mockMvc.perform(get("/audit/events/verify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(false))
            .andExpect(jsonPath("$.violation.sequenceId").value(3))
            .andExpect(jsonPath("$.violation.violationType").value("SEQUENCE_GAP"))
            .andExpect(jsonPath("$.violation.details").value("Expected sequence_id 2, but found 3"));
    }

    @Test
    void testVerifyWithLastNParameter() throws Exception {
        // Create chain of 5 events
        createValidChain(5);

        // Verify last 2 records only
        mockMvc.perform(get("/audit/verify?lastN=2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(true))
            .andExpect(jsonPath("$.totalRecords").value(5))
            .andExpect(jsonPath("$.verifiedRecordsCount").value(2))
            .andExpect(jsonPath("$.lastVerifiedSequenceId").value(5));
    }

    @Test
    void testVerifyWithLastNDetectsViolationInWindow() throws Exception {
        // Create chain of 5 events
        createValidChain(5);

        // Corrupt record 5 (which is in the last 2)
        disableTrigger();
        jdbcTemplate.execute("UPDATE audit_events SET payload = '{\"corrupted\": true}' WHERE sequence_id = 5");
        enableTrigger();

        mockMvc.perform(get("/audit/verify?lastN=2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(false))
            .andExpect(jsonPath("$.violation.sequenceId").value(5));
    }

    @Test
    void testVerifyWithLastNIgnoresViolationOutsideWindow() throws Exception {
        // Create chain of 5 events
        createValidChain(5);

        // Corrupt record 2 (outside the last 2 window)
        disableTrigger();
        jdbcTemplate.execute("UPDATE audit_events SET payload = '{\"corrupted\": true}' WHERE sequence_id = 2");
        enableTrigger();

        mockMvc.perform(get("/audit/verify?lastN=2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(true));
    }

    @Test
    void testVerifyLogsViolationAsAuditEvent() throws Exception {
        // Create valid chain
        createValidChain(3);

        // Corrupt record 2
        disableTrigger();
        jdbcTemplate.execute("UPDATE audit_events SET payload = '{\"corrupted\": true}' WHERE sequence_id = 2");
        enableTrigger();

        mockMvc.perform(get("/audit/events/verify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(false));

        // Check that violation was logged as audit event
        long auditEventCount = auditEventRepository.count();
        org.assertj.core.api.Assertions.assertThat(auditEventCount).isGreaterThan(3); // Original 3 + violation log

        AuditEvent violationLog = auditEventRepository.findAll()
            .stream()
            .filter(e -> "AUDIT_CHAIN_VIOLATION".equals(e.getEventType()))
            .findFirst()
            .orElseThrow();

        Assertions.assertThat(violationLog.getPayload()).contains("CONTENT_HASH_MISMATCH");
        Assertions.assertThat(violationLog.getPayload()).contains("sequenceId");
    }

    private void createValidChain(int count) {
        AuditEvent previous = null;
        for (int i = 1; i <= count; i++) {
            AuditEvent event = AuditEvent.builder()
                .eventType("TEST_EVENT_" + i)
                .actorId("actor-1")
                .resourceType("TEST")
                .resourceId("test-" + i)
                .payload("{\"index\": " + i + "}")
                .build();
            auditEventHasher.computeHash(event, previous);
            previous = auditEventRepository.save(event);
        }
    }
}

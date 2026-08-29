package com.persistent.auditlog.api;

import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.domain.AuditEventHasher;
import com.persistent.auditlog.repository.AuditEventRepository;
import com.persistent.auditlog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditEventRetentionIT extends AbstractIntegrationTest {

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
        mockMvc = authenticatedMockMvc(context);
        jdbcTemplate.execute("TRUNCATE TABLE audit_events RESTART IDENTITY CASCADE");
    }

    private AuditEvent seedEventWithServerTimestamp(AuditEvent previous, Instant serverTimestamp) {
        AuditEvent event = AuditEvent.builder()
            .eventType("TEST_EVENT")
            .actorId("actor-1")
            .resourceType("TEST")
            .resourceId("res-1")
            .payload("{}")
            .serverTimestamp(serverTimestamp)
            .build();
        auditEventHasher.computeHash(event, previous);
        return auditEventRepository.save(event);
    }

    @Test
    void archiveOnlyArchivesEligibleOldNonArchivedRecords() throws Exception {
        Instant now = Instant.now();
        AuditEvent old1 = seedEventWithServerTimestamp(null, now.minus(400, ChronoUnit.DAYS));
        AuditEvent old2 = seedEventWithServerTimestamp(old1, now.minus(370, ChronoUnit.DAYS));
        AuditEvent recent = seedEventWithServerTimestamp(old2, now.minus(10, ChronoUnit.DAYS));

        mockMvc.perform(post("/audit/retention/archive").param("windowDays", "365"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.archivedCount").value(2))
            .andExpect(jsonPath("$.windowDaysUsed").value(365));

        assertThat(auditEventRepository.findBySequenceId(old1.getSequenceId()).orElseThrow().getArchivedAt()).isNotNull();
        assertThat(auditEventRepository.findBySequenceId(old2.getSequenceId()).orElseThrow().getArchivedAt()).isNotNull();
        assertThat(auditEventRepository.findBySequenceId(recent.getSequenceId()).orElseThrow().getArchivedAt()).isNull();
    }

    @Test
    void secondArchiveCallIsANoOpForAlreadyArchivedRecords() throws Exception {
        Instant now = Instant.now();
        seedEventWithServerTimestamp(null, now.minus(400, ChronoUnit.DAYS));

        mockMvc.perform(post("/audit/retention/archive").param("windowDays", "365"))
            .andExpect(jsonPath("$.archivedCount").value(1));

        mockMvc.perform(post("/audit/retention/archive").param("windowDays", "365"))
            .andExpect(jsonPath("$.archivedCount").value(0));
    }

    @Test
    void windowDaysOverrideChangesEligibleSet() throws Exception {
        Instant now = Instant.now();
        seedEventWithServerTimestamp(null, now.minus(40, ChronoUnit.DAYS));

        mockMvc.perform(post("/audit/retention/archive").param("windowDays", "365"))
            .andExpect(jsonPath("$.archivedCount").value(0));

        mockMvc.perform(post("/audit/retention/archive").param("windowDays", "30"))
            .andExpect(jsonPath("$.archivedCount").value(1));
    }

    @Test
    void directSqlUpdateOfNonArchivalColumnIsStillRejected() {
        seedEventWithServerTimestamp(null, Instant.now());

        assertThatTriggerRejects("UPDATE audit_events SET payload = '{\"x\":1}' WHERE sequence_id = 1");
    }

    @Test
    void directSqlUpdateCombiningArchivalWithAnotherColumnChangeIsRejected() {
        seedEventWithServerTimestamp(null, Instant.now());

        assertThatTriggerRejects(
            "UPDATE audit_events SET archived_at = now(), payload = '{\"x\":1}' WHERE sequence_id = 1");
    }

    @Test
    void directSqlUnarchivingIsRejected() {
        seedEventWithServerTimestamp(null, Instant.now());
        jdbcTemplate.execute("UPDATE audit_events SET archived_at = now() WHERE sequence_id = 1");

        assertThatTriggerRejects("UPDATE audit_events SET archived_at = NULL WHERE sequence_id = 1");
    }

    @Test
    void deleteOnArchivedRowIsStillRejected() {
        seedEventWithServerTimestamp(null, Instant.now());
        jdbcTemplate.execute("UPDATE audit_events SET archived_at = now() WHERE sequence_id = 1");

        assertThatTriggerRejects("DELETE FROM audit_events WHERE sequence_id = 1");
    }

    private void assertThatTriggerRejects(String sql) {
        try {
            jdbcTemplate.execute(sql);
            throw new AssertionError("Expected trigger to reject: " + sql);
        } catch (org.springframework.dao.DataAccessException e) {
            assertThat(e.getMessage()).contains("immutable");
        }
    }
}

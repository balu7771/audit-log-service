package com.persistent.auditlog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.repository.AuditEventRepository;
import com.persistent.auditlog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditEventControllerTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = authenticatedMockMvc(context);
        objectMapper = new ObjectMapper();
        jdbcTemplate.execute("TRUNCATE TABLE audit_events RESTART IDENTITY");
    }

    @Test
    void testPostAuditEventSuccessfullyPersistsWithCorrectHashLinkage() throws Exception {
        String requestBody = objectMapper.writeValueAsString(new CreateAuditEventRequest(
            "USER_LOGIN",
            "user-123",
            "USER",
            "user-123",
            "{\"ip\": \"192.168.1.1\"}",
            null
        ));

        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sequenceId").value(1))
            .andExpect(jsonPath("$.contentHash").isNotEmpty())
            .andExpect(jsonPath("$.recordHash").isNotEmpty())
            .andExpect(jsonPath("$.previousHash").value("0000000000000000000000000000000000000000000000000000000000000000"));

        AuditEvent savedEvent = auditEventRepository.findBySequenceId(1L).orElseThrow();
        assertThat(savedEvent.getContentHash()).isNotNull().hasSize(64);
        assertThat(savedEvent.getRecordHash()).isNotNull().hasSize(64);
    }

    @Test
    void testPostMultipleEventsLinksChainCorrectly() throws Exception {
        // First event
        String request1 = objectMapper.writeValueAsString(new CreateAuditEventRequest(
            "EVENT_1", "actor-1", "RESOURCE", "res-1", "{}", null
        ));
        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(request1))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sequenceId").value(1));

        AuditEvent event1 = auditEventRepository.findBySequenceId(1L).orElseThrow();

        // Second event
        String request2 = objectMapper.writeValueAsString(new CreateAuditEventRequest(
            "EVENT_2", "actor-1", "RESOURCE", "res-2", "{}", null
        ));
        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(request2))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sequenceId").value(2))
            .andExpect(jsonPath("$.previousHash").value(event1.getRecordHash()));

        AuditEvent event2 = auditEventRepository.findBySequenceId(2L).orElseThrow();
        assertThat(event2.getPreviousHash()).isEqualTo(event1.getRecordHash());
    }

    @Test
    void testPostWithMissingRequiredFieldReturns400() throws Exception {
        String invalidRequestBody = objectMapper.writeValueAsString(new CreateAuditEventRequest(
            null, // missing eventType
            "actor-1",
            "RESOURCE",
            "res-1",
            "{}",
            null
        ));

        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalidRequestBody))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testFailedPostAttemptsAreLoggedAsAuditEvents() throws Exception {
        // Attempt with missing eventType
        String invalidRequest = objectMapper.writeValueAsString(new CreateAuditEventRequest(
            null,
            "actor-1",
            "RESOURCE",
            "res-1",
            "{}",
            null
        ));

        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalidRequest))
            .andExpect(status().isBadRequest());

        // Check that a failure audit event was logged
        long auditEventCount = auditEventRepository.count();
        assertThat(auditEventCount).isGreaterThan(0);

        AuditEvent failureEvent = auditEventRepository.findAll().get(0);
        assertThat(failureEvent.getEventType()).isEqualTo("AUDIT_EVENT_WRITE_FAILURE");
        assertThat(failureEvent.getPayload()).contains("VALIDATION_ERROR");
        assertThat(failureEvent.getPayload()).contains("eventType");
    }

    @Test
    void testPutOnAuditEventsReturnsMethodNotAllowed() throws Exception {
        String requestBody = objectMapper.writeValueAsString(new CreateAuditEventRequest(
            "EVENT_1", "actor-1", "RESOURCE", "res-1", "{}", null
        ));

        mockMvc.perform(put("/audit/events/1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
            .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void testPatchOnAuditEventsReturnsMethodNotAllowed() throws Exception {
        String requestBody = objectMapper.writeValueAsString(new CreateAuditEventRequest(
            "EVENT_1", "actor-1", "RESOURCE", "res-1", "{}", null
        ));

        mockMvc.perform(patch("/audit/events/1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
            .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void testDeleteOnAuditEventsReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/audit/events/1"))
            .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void testDatabaseTriggerRejectsDirectUpdate() throws Exception {
        // First, create an event via API
        String requestBody = objectMapper.writeValueAsString(new CreateAuditEventRequest(
            "EVENT_1", "actor-1", "RESOURCE", "res-1", "{}", null
        ));
        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
            .andExpect(status().isCreated());

        // Try to update via direct SQL (simulating DBeaver attempt)
        try {
            jdbcTemplate.execute("UPDATE audit_events SET event_type = 'HACKED' WHERE sequence_id = 1");
            throw new AssertionError("Expected trigger to reject UPDATE");
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("immutable");
        }
    }

    @Test
    void testDatabaseTriggerRejectsDirectDelete() throws Exception {
        // First, create an event via API
        String requestBody = objectMapper.writeValueAsString(new CreateAuditEventRequest(
            "EVENT_1", "actor-1", "RESOURCE", "res-1", "{}", null
        ));
        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
            .andExpect(status().isCreated());

        // Try to delete via direct SQL (simulating DBeaver attempt)
        try {
            jdbcTemplate.execute("DELETE FROM audit_events WHERE sequence_id = 1");
            throw new AssertionError("Expected trigger to reject DELETE");
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("immutable");
        }
    }

    // Helper class for request body
    public static class CreateAuditEventRequest {
        public String eventType;
        public String actorId;
        public String resourceType;
        public String resourceId;
        public String payload;
        public String clientTimestamp;

        public CreateAuditEventRequest(String eventType, String actorId, String resourceType,
                                       String resourceId, String payload, String clientTimestamp) {
            this.eventType = eventType;
            this.actorId = actorId;
            this.resourceType = resourceType;
            this.resourceId = resourceId;
            this.payload = payload;
            this.clientTimestamp = clientTimestamp;
        }
    }
}

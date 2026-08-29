package com.persistent.auditlog.api;

import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.repository.AuditEventRepository;
import com.persistent.auditlog.repository.RedactionKeyRepository;
import com.persistent.auditlog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditEventRedactionIT extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private RedactionKeyRepository redactionKeyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = authenticatedMockMvc(context);
        jdbcTemplate.execute("TRUNCATE TABLE audit_events RESTART IDENTITY CASCADE");
    }

    private String createWithSensitiveSsnRequestBody() {
        return "{"
            + "\"eventType\":\"USER_UPDATED\","
            + "\"actorId\":\"actor-1\","
            + "\"resourceType\":\"USER\","
            + "\"resourceId\":\"user-1\","
            + "\"payload\":\"{\\\"ssn\\\":\\\"123-45-6789\\\",\\\"name\\\":\\\"Jane\\\"}\","
            + "\"sensitiveFields\":[\"ssn\"]"
            + "}";
    }

    @Test
    void createWithSensitiveFieldEncryptsPayloadAtRestAndDecryptsOnRead() throws Exception {
        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createWithSensitiveSsnRequestBody()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.payload").value(containsString("123-45-6789")))
            .andExpect(jsonPath("$.sensitiveFields[0]").value("ssn"));

        AuditEvent stored = auditEventRepository.findBySequenceId(1L).orElseThrow();
        assertThat(stored.getPayload()).doesNotContain("123-45-6789");
        assertThat(stored.getPayload()).contains("__enc");
        assertThat(redactionKeyRepository.findByIdSequenceId(1L)).hasSize(1);

        mockMvc.perform(get("/audit/events").param("resourceType", "USER").param("resourceId", "user-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].payload").value(containsString("123-45-6789")));
    }

    @Test
    void redactingFieldReplacesItWithPlaceholderAndKeepsHashChainIntact() throws Exception {
        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createWithSensitiveSsnRequestBody()))
            .andExpect(status().isCreated());

        AuditEvent before = auditEventRepository.findBySequenceId(1L).orElseThrow();

        mockMvc.perform(post("/audit/events/1/redactions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fieldPath\":\"ssn\",\"actorId\":\"compliance-officer\",\"reason\":\"privacy request\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.alreadyRedacted").value(false))
            .andExpect(jsonPath("$.redactionAuditEventSequenceId").value(2));

        mockMvc.perform(get("/audit/events")
                .param("resourceType", "USER").param("resourceId", "user-1").param("eventType", "USER_UPDATED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].payload").value(containsString("[REDACTED]")));

        AuditEvent after = auditEventRepository.findBySequenceId(1L).orElseThrow();
        assertThat(after.getContentHash()).isEqualTo(before.getContentHash());
        assertThat(after.getRecordHash()).isEqualTo(before.getRecordHash());

        mockMvc.perform(get("/audit/verify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(true));

        AuditEvent redactionEvent = auditEventRepository.findBySequenceId(2L).orElseThrow();
        assertThat(redactionEvent.getEventType()).isEqualTo("FIELD_REDACTED");

        assertThat(redactionKeyRepository.findByIdSequenceIdAndIdFieldPath(1L, "ssn")).isEmpty();
    }

    @Test
    void redactingSameFieldTwiceIsIdempotent() throws Exception {
        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createWithSensitiveSsnRequestBody()))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/audit/events/1/redactions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fieldPath\":\"ssn\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.alreadyRedacted").value(false));

        mockMvc.perform(post("/audit/events/1/redactions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fieldPath\":\"ssn\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.alreadyRedacted").value(true))
            .andExpect(jsonPath("$.redactionAuditEventSequenceId").doesNotExist());

        long redactionEventCount = auditEventRepository.findAll().stream()
            .filter(e -> "FIELD_REDACTED".equals(e.getEventType()))
            .count();
        assertThat(redactionEventCount).isEqualTo(1);
    }

    @Test
    void redactingFieldNotDeclaredSensitiveReturns400() throws Exception {
        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createWithSensitiveSsnRequestBody()))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/audit/events/1/redactions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fieldPath\":\"name\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void creatingWithUnknownSensitiveFieldReturns400() throws Exception {
        String requestBody = "{"
            + "\"eventType\":\"USER_UPDATED\","
            + "\"actorId\":\"actor-1\","
            + "\"resourceType\":\"USER\","
            + "\"resourceId\":\"user-1\","
            + "\"payload\":\"{\\\"name\\\":\\\"Jane\\\"}\","
            + "\"sensitiveFields\":[\"doesNotExist\"]"
            + "}";

        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
            .andExpect(status().isBadRequest());
    }
}

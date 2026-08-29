package com.persistent.auditlog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.persistent.auditlog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditExportTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = authenticatedMockMvc(context);
        objectMapper = new ObjectMapper();
        jdbcTemplate.execute("TRUNCATE TABLE audit_events RESTART IDENTITY CASCADE");
    }

    private void createEvent(String eventType, String resourceType, String resourceId, String actorId, String payload) throws Exception {
        String body = String.format(
            "{\"eventType\":\"%s\",\"actorId\":\"%s\",\"resourceType\":\"%s\",\"resourceId\":\"%s\",\"payload\":%s}",
            eventType, actorId, resourceType, resourceId, objectMapper.writeValueAsString(payload));
        mockMvc.perform(post("/audit/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
            .andExpect(status().isCreated());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportAsMap(String resourceType, String resourceId) throws Exception {
        MvcResult result = mockMvc.perform(get("/audit/export")
                .param("resourceType", resourceType)
                .param("resourceId", resourceId))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    @Test
    void exportByResourceReturnsOnlyMatchingRecordsWithManifestAndSignature() throws Exception {
        createEvent("USER_LOGIN", "USER", "user-1", "actor-1", "{}");
        createEvent("USER_LOGOUT", "USER", "user-1", "actor-1", "{}");
        createEvent("OTHER_EVENT", "USER", "user-2", "actor-2", "{}");

        mockMvc.perform(get("/audit/export").param("resourceType", "USER").param("resourceId", "user-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordCount").value(2))
            .andExpect(jsonPath("$.records[0].sequenceId").value(1))
            .andExpect(jsonPath("$.records[1].sequenceId").value(2))
            .andExpect(jsonPath("$.manifest.manifestHash").isNotEmpty())
            .andExpect(jsonPath("$.signature.value").isNotEmpty());
    }

    @Test
    void verifyOnUntamperedBundleReportsValid() throws Exception {
        createEvent("USER_LOGIN", "USER", "user-1", "actor-1", "{}");
        createEvent("USER_LOGOUT", "USER", "user-1", "actor-1", "{}");

        Map<String, Object> bundle = exportAsMap("USER", "user-1");

        mockMvc.perform(post("/audit/export/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bundle)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.perRecordIntact").value(true))
            .andExpect(jsonPath("$.manifestIntact").value(true))
            .andExpect(jsonPath("$.signatureIntact").value(true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifyDetectsTamperedRecordPayload() throws Exception {
        createEvent("USER_LOGIN", "USER", "user-1", "actor-1", "{}");

        Map<String, Object> bundle = exportAsMap("USER", "user-1");
        Map<String, Object> record = ((java.util.List<Map<String, Object>>) bundle.get("records")).get(0);
        record.put("storedPayload", "{\"tampered\":true}");

        mockMvc.perform(post("/audit/export/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bundle)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.perRecordIntact").value(false))
            .andExpect(jsonPath("$.firstViolation.violationType").value("CONTENT_HASH_MISMATCH"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifyDetectsTamperedManifestWithoutTouchingRecords() throws Exception {
        createEvent("USER_LOGIN", "USER", "user-1", "actor-1", "{}");

        Map<String, Object> bundle = exportAsMap("USER", "user-1");
        Map<String, Object> manifest = (Map<String, Object>) bundle.get("manifest");
        manifest.put("manifestHash", "0000000000000000000000000000000000000000000000000000000000000000");

        mockMvc.perform(post("/audit/export/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bundle)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.perRecordIntact").value(true))
            .andExpect(jsonPath("$.manifestIntact").value(false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifyDetectsTamperedSignature() throws Exception {
        createEvent("USER_LOGIN", "USER", "user-1", "actor-1", "{}");

        Map<String, Object> bundle = exportAsMap("USER", "user-1");
        Map<String, Object> signature = (Map<String, Object>) bundle.get("signature");
        signature.put("value", "deadbeef");

        mockMvc.perform(post("/audit/export/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bundle)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.signatureIntact").value(false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifyDetectsRemovedRecordViaManifestMismatch() throws Exception {
        createEvent("USER_LOGIN", "USER", "user-1", "actor-1", "{}");
        createEvent("USER_LOGOUT", "USER", "user-1", "actor-1", "{}");

        Map<String, Object> bundle = exportAsMap("USER", "user-1");
        java.util.List<Map<String, Object>> records = (java.util.List<Map<String, Object>>) bundle.get("records");
        records.remove(1);
        bundle.put("recordCount", 1);

        mockMvc.perform(post("/audit/export/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bundle)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.manifestIntact").value(false));
    }

    @Test
    void exportRequiresResourceTypeWhenResourceIdGiven() throws Exception {
        mockMvc.perform(get("/audit/export").param("resourceId", "user-1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void exportRejectsBothActorIdAndResourceTypeTogether() throws Exception {
        mockMvc.perform(get("/audit/export").param("actorId", "actor-1").param("resourceType", "USER"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void exportRequiresSomeFilter() throws Exception {
        mockMvc.perform(get("/audit/export"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void exportByActorIdWithNoMatchesReturnsEmptyBundleThatVerifiesTrue() throws Exception {
        Map<String, Object> bundle = null;
        MvcResult result = mockMvc.perform(get("/audit/export").param("actorId", "no-such-actor"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordCount").value(0))
            .andReturn();
        bundle = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);

        mockMvc.perform(post("/audit/export/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bundle)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void exportShowsRedactedFieldAsPlaceholderInDisplayPayloadButVerifiesUsingStoredCiphertext() throws Exception {
        String requestBody = "{"
            + "\"eventType\":\"USER_UPDATED\","
            + "\"actorId\":\"actor-1\","
            + "\"resourceType\":\"USER\","
            + "\"resourceId\":\"user-1\","
            + "\"payload\":\"{\\\"ssn\\\":\\\"123-45-6789\\\"}\","
            + "\"sensitiveFields\":[\"ssn\"]"
            + "}";
        mockMvc.perform(post("/audit/events").contentType(MediaType.APPLICATION_JSON).content(requestBody))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/audit/events/1/redactions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fieldPath\":\"ssn\"}"))
            .andExpect(status().isOk());

        Map<String, Object> bundle = exportAsMap("USER", "user-1");

        mockMvc.perform(get("/audit/export").param("resourceType", "USER").param("resourceId", "user-1"))
            .andExpect(jsonPath("$.records[0].payload").value(org.hamcrest.Matchers.containsString("[REDACTED]")));

        mockMvc.perform(post("/audit/export/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bundle)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true));
    }
}

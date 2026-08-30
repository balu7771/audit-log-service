package com.persistent.auditlog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.persistent.auditlog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the RBAC model directly: each of the three roles (WRITER,
 * AUDITOR, ADMIN) is checked against every endpoint, confirming both that
 * the role it's meant for is allowed through and that the roles it isn't
 * meant for are rejected with 403 - i.e. that Scenario A's and Scenario B's
 * callers can no longer share one all-access token (see
 * docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md).
 */
class RoleBasedAccessControlTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jdbcTemplate.execute("TRUNCATE TABLE audit_events RESTART IDENTITY CASCADE");
    }

    private String createEventRequestBody() throws Exception {
        return objectMapper.writeValueAsString(new CreateAuditEventRequest(
            "USER_LOGIN", "user-1", "USER", "user-1", "{}", null, null));
    }

    // --- WRITER: can create events, cannot read/export/administer ---

    @Test
    void writerCanCreateEvents() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_WRITER_API_KEY);
        mockMvc.perform(post("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createEventRequestBody()))
            .andExpect(status().isCreated());
    }

    @Test
    void writerCannotQueryEvents() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_WRITER_API_KEY);
        mockMvc.perform(get("/audit/events")).andExpect(status().isForbidden());
    }

    @Test
    void writerCannotVerifyChain() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_WRITER_API_KEY);
        mockMvc.perform(get("/audit/verify")).andExpect(status().isForbidden());
    }

    @Test
    void writerCannotExport() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_WRITER_API_KEY);
        mockMvc.perform(get("/audit/export").param("actorId", "user-1")).andExpect(status().isForbidden());
    }

    @Test
    void writerCannotArchive() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_WRITER_API_KEY);
        mockMvc.perform(post("/audit/retention/archive")).andExpect(status().isForbidden());
    }

    @Test
    void writerCannotRedact() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_WRITER_API_KEY);
        mockMvc.perform(post("/audit/events/1/redactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RedactFieldRequest("someField", "user-1", "test"))))
            .andExpect(status().isForbidden());
    }

    // --- AUDITOR: read-only, cannot write/administer ---

    @Test
    void auditorCanQueryEvents() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_AUDITOR_API_KEY);
        mockMvc.perform(get("/audit/events")).andExpect(status().isOk());
    }

    @Test
    void auditorCanVerifyChain() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_AUDITOR_API_KEY);
        mockMvc.perform(get("/audit/verify")).andExpect(status().isOk());
    }

    @Test
    void auditorCanExportAndVerifyBundle() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_AUDITOR_API_KEY);
        mockMvc.perform(get("/audit/export").param("actorId", "user-1")).andExpect(status().isOk());
        mockMvc.perform(post("/audit/export/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }

    @Test
    void auditorCannotCreateEvents() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_AUDITOR_API_KEY);
        mockMvc.perform(post("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createEventRequestBody()))
            .andExpect(status().isForbidden());
    }

    @Test
    void auditorCannotArchive() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_AUDITOR_API_KEY);
        mockMvc.perform(post("/audit/retention/archive")).andExpect(status().isForbidden());
    }

    @Test
    void auditorCannotRedact() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_AUDITOR_API_KEY);
        mockMvc.perform(post("/audit/events/1/redactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RedactFieldRequest("someField", "user-1", "test"))))
            .andExpect(status().isForbidden());
    }

    // --- ADMIN: superuser via role hierarchy (implies AUDITOR and WRITER) ---

    @Test
    void adminCanCreateQueryVerifyAndExport() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_ADMIN_API_KEY);
        mockMvc.perform(post("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createEventRequestBody()))
            .andExpect(status().isCreated());
        mockMvc.perform(get("/audit/events")).andExpect(status().isOk());
        mockMvc.perform(get("/audit/verify")).andExpect(status().isOk());
        mockMvc.perform(get("/audit/export").param("actorId", "user-1")).andExpect(status().isOk());
    }

    @Test
    void adminCanArchive() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_ADMIN_API_KEY);
        mockMvc.perform(post("/audit/retention/archive")).andExpect(status().isOk());
    }

    // --- Cross-cutting ---

    @Test
    void forbiddenResponseExplainsRoleMismatch() throws Exception {
        MockMvc mockMvc = mockMvcAs(context, TEST_WRITER_API_KEY);
        mockMvc.perform(get("/audit/events"))
            .andExpect(status().isForbidden())
            .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("Forbidden"));
    }
}

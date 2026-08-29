package com.persistent.auditlog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.domain.AuditEventHasher;
import com.persistent.auditlog.repository.AuditEventRepository;
import com.persistent.auditlog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditEventQueryTest extends AbstractIntegrationTest {

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
        seedTestData();
    }

    private void seedTestData() {
        // Create 5 events with different actors/resources/types for filtering
        AuditEvent event1 = AuditEvent.builder()
            .eventType("USER_LOGIN")
            .actorId("user-1")
            .resourceType("USER")
            .resourceId("user-1")
            .payload("{}")
            .build();
        auditEventHasher.computeHash(event1, null);
        auditEventRepository.save(event1);

        AuditEvent event2 = AuditEvent.builder()
            .eventType("DATA_READ")
            .actorId("user-1")
            .resourceType("DOCUMENT")
            .resourceId("doc-123")
            .payload("{}")
            .build();
        auditEventHasher.computeHash(event2, event1);
        auditEventRepository.save(event2);

        AuditEvent event3 = AuditEvent.builder()
            .eventType("USER_LOGIN")
            .actorId("user-2")
            .resourceType("USER")
            .resourceId("user-2")
            .payload("{}")
            .build();
        auditEventHasher.computeHash(event3, event2);
        auditEventRepository.save(event3);

        AuditEvent event4 = AuditEvent.builder()
            .eventType("DATA_WRITE")
            .actorId("user-2")
            .resourceType("DOCUMENT")
            .resourceId("doc-456")
            .payload("{}")
            .build();
        auditEventHasher.computeHash(event4, event3);
        auditEventRepository.save(event4);

        AuditEvent event5 = AuditEvent.builder()
            .eventType("DATA_READ")
            .actorId("user-3")
            .resourceType("DOCUMENT")
            .resourceId("doc-789")
            .payload("{}")
            .build();
        auditEventHasher.computeHash(event5, event4);
        auditEventRepository.save(event5);
    }

    @Test
    void testGetAllEventsWithDefaultPagination() throws Exception {
        mockMvc.perform(get("/audit/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()").value(5))
            .andExpect(jsonPath("$.pageable.pageNumber").value(0))
            .andExpect(jsonPath("$.pageable.pageSize").value(20))
            .andExpect(jsonPath("$.pageable.totalElements").value(5));
    }

    @Test
    void testFilterByActorId() throws Exception {
        mockMvc.perform(get("/audit/events?actorId=user-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].actorId").value("user-1"))
            .andExpect(jsonPath("$.content[1].actorId").value("user-1"));
    }

    @Test
    void testFilterByEventType() throws Exception {
        mockMvc.perform(get("/audit/events?eventType=DATA_READ"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].eventType").value("DATA_READ"))
            .andExpect(jsonPath("$.content[1].eventType").value("DATA_READ"));
    }

    @Test
    void testFilterByResourceTypeAndResourceId() throws Exception {
        mockMvc.perform(get("/audit/events?resourceType=DOCUMENT&resourceId=doc-123"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].resourceId").value("doc-123"));
    }

    @Test
    void testFilterByResourceIdWithoutResourceTypeReturns400() throws Exception {
        mockMvc.perform(get("/audit/events?resourceId=doc-123"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testCombineMultipleFilters() throws Exception {
        mockMvc.perform(get("/audit/events?actorId=user-1&eventType=DATA_READ"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].actorId").value("user-1"))
            .andExpect(jsonPath("$.content[0].eventType").value("DATA_READ"));
    }

    @Test
    void testFilterByTimeRange() throws Exception {
        AuditEvent firstEvent = auditEventRepository.findBySequenceId(1L).orElseThrow();
        AuditEvent lastEvent = auditEventRepository.findBySequenceId(5L).orElseThrow();

        Instant midpoint = firstEvent.getServerTimestamp().plusSeconds(10);

        // Should get first 2-3 events (before midpoint)
        mockMvc.perform(get("/audit/events?to=" + midpoint))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").isNumber());

        // Should get last 2-3 events (after midpoint)
        mockMvc.perform(get("/audit/events?from=" + midpoint))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").isNumber());
    }

    @Test
    void testPaginationWithPageAndSize() throws Exception {
        mockMvc.perform(get("/audit/events?page=0&size=2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.pageable.pageNumber").value(0))
            .andExpect(jsonPath("$.pageable.pageSize").value(2))
            .andExpect(jsonPath("$.pageable.totalElements").value(5))
            .andExpect(jsonPath("$.pageable.totalPages").value(3));

        mockMvc.perform(get("/audit/events?page=1&size=2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.pageable.pageNumber").value(1));

        mockMvc.perform(get("/audit/events?page=2&size=2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.pageable.pageNumber").value(2));
    }

    @Test
    void testPageSizeExceedingMaxReturns400() throws Exception {
        mockMvc.perform(get("/audit/events?size=101"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testResultsOrderedBySequenceIdDescByDefault() throws Exception {
        mockMvc.perform(get("/audit/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].sequenceId").value(5))
            .andExpect(jsonPath("$.content[1].sequenceId").value(4))
            .andExpect(jsonPath("$.content[2].sequenceId").value(3))
            .andExpect(jsonPath("$.content[3].sequenceId").value(2))
            .andExpect(jsonPath("$.content[4].sequenceId").value(1));
    }

    @Test
    void testNoResultsReturnsEmptyContent() throws Exception {
        mockMvc.perform(get("/audit/events?actorId=nonexistent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.pageable.totalElements").value(0));
    }
}

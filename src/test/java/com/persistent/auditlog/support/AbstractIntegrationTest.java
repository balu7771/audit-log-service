package com.persistent.auditlog.support;

import com.persistent.auditlog.config.ApiKeyAuthFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // Must match audit.security.api-keys in application-test.yml. ADMIN implies both
    // AUDITOR and WRITER (see SecurityConfig's role hierarchy), so TEST_API_KEY is kept
    // as the default "can call anything" key for tests that aren't specifically about
    // RBAC; tests that assert role boundaries use the scoped keys directly.
    protected static final String TEST_WRITER_API_KEY = "test-writer-key";
    protected static final String TEST_AUDITOR_API_KEY = "test-auditor-key";
    protected static final String TEST_ADMIN_API_KEY = "test-admin-key";
    protected static final String TEST_API_KEY = TEST_ADMIN_API_KEY;

    // Singleton container pattern: started once for the whole JVM and never stopped by JUnit,
    // since a static @Container field inherited by every subclass gets torn down by the
    // @Testcontainers extension after the FIRST test class finishes, breaking every class after it.
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Every /audit/** request needs X-API-Key; setting it as a default request
    // header here avoids repeating it on every mockMvc.perform(...) call.
    protected MockMvc authenticatedMockMvc(WebApplicationContext context) {
        return mockMvcAs(context, TEST_API_KEY);
    }

    // For tests that specifically assert RBAC boundaries between roles.
    //
    // .apply(springSecurity()) wires the real springSecurityFilterChain bean into MockMvc's
    // simulated dispatch. Without it, MockMvc's webAppContextSetup never runs any servlet
    // filter (Spring Security's included) — only the @PreAuthorize AOP interceptor around
    // the controller bean would still fire, and with no filter to populate the
    // SecurityContext it would always see "no Authentication" regardless of the header set
    // below, which does not reflect how a real request (see ApiKeyAuthFilterTest, which
    // exercises the real embedded server) is actually authenticated.
    protected MockMvc mockMvcAs(WebApplicationContext context, String apiKey) {
        return MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .defaultRequest(get("/").header(ApiKeyAuthFilter.API_KEY_HEADER, apiKey))
            .build();
    }
}

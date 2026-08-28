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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // Must match audit.security.api-key in application-test.yml.
    protected static final String TEST_API_KEY = "test-api-key";

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
        return MockMvcBuilders.webAppContextSetup(context)
            .defaultRequest(get("/").header(ApiKeyAuthFilter.API_KEY_HEADER, TEST_API_KEY))
            .build();
    }
}

package com.persistent.auditlog.config;

import com.persistent.auditlog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthFilterTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void requestWithoutApiKeyIsRejected() {
        ResponseEntity<String> response = restTemplate.getForEntity("/audit/events", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requestWithWrongApiKeyIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(ApiKeyAuthFilter.API_KEY_HEADER, "wrong-key");

        ResponseEntity<String> response = restTemplate.exchange(
            "/audit/events", org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requestWithCorrectApiKeyIsAccepted() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(ApiKeyAuthFilter.API_KEY_HEADER, TEST_API_KEY);

        ResponseEntity<String> response = restTemplate.exchange(
            "/audit/events", org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void actuatorHealthDoesNotRequireApiKey() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

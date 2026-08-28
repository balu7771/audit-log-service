package com.persistent.auditlog.domain;

import com.persistent.auditlog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventHashChainTest extends AbstractIntegrationTest {

    @Autowired
    private AuditEventHasher auditEventHasher;

    @Test
    void testGenesisRecordHasAllZerosPreviousHash() {
        AuditEvent genesis = AuditEvent.builder()
            .eventType("USER_LOGIN")
            .actorId("actor-1")
            .resourceType("USER")
            .resourceId("resource-1")
            .payload("{\"details\": \"test\"}")
            .build();

        auditEventHasher.computeHash(genesis, null);

        assertThat(genesis.getPreviousHash())
            .isEqualTo("0000000000000000000000000000000000000000000000000000000000000000");
    }

    @Test
    void testHashChainLinksCorrectlyBetweenConsecutiveEvents() {
        AuditEvent event1 = AuditEvent.builder()
            .eventType("USER_LOGIN")
            .actorId("actor-1")
            .resourceType("USER")
            .resourceId("resource-1")
            .payload("{\"details\": \"test\"}")
            .build();

        auditEventHasher.computeHash(event1, null);

        AuditEvent event2 = AuditEvent.builder()
            .eventType("DATA_READ")
            .actorId("actor-1")
            .resourceType("DOCUMENT")
            .resourceId("doc-123")
            .payload("{\"document_id\": \"doc-123\"}")
            .build();

        auditEventHasher.computeHash(event2, event1);

        // Event 2's previousHash should be Event 1's recordHash
        assertThat(event2.getPreviousHash()).isEqualTo(event1.getRecordHash());

        // Event 2's recordHash should be SHA-256 of (contentHash || previousHash)
        String expectedRecordHash = computeSha256(event2.getContentHash() + event2.getPreviousHash());
        assertThat(event2.getRecordHash()).isEqualTo(expectedRecordHash);
    }

    @Test
    void testCanonicalSerializationIsStable() {
        // Pin serverTimestamp so it doesn't vary between the two events under comparison
        java.time.Instant fixedTimestamp = java.time.Instant.now();

        // Create event with fields in one order
        AuditEvent event1 = AuditEvent.builder()
            .eventType("USER_LOGIN")
            .actorId("actor-1")
            .resourceType("USER")
            .resourceId("resource-1")
            .payload("{\"z\": 1, \"a\": 2}")
            .serverTimestamp(fixedTimestamp)
            .build();

        auditEventHasher.computeHash(event1, null);
        String hash1 = event1.getContentHash();

        // Create another event with same data but different JSON key order
        AuditEvent event2 = AuditEvent.builder()
            .eventType("USER_LOGIN")
            .actorId("actor-1")
            .resourceType("USER")
            .resourceId("resource-1")
            .payload("{\"a\": 2, \"z\": 1}")
            .serverTimestamp(fixedTimestamp)
            .build();

        auditEventHasher.computeHash(event2, null);
        String hash2 = event2.getContentHash();

        // contentHash should be identical regardless of payload JSON key order
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void testClientTimestampExcludedFromContentHash() {
        // Pin serverTimestamp so it doesn't vary between the two events under comparison
        java.time.Instant fixedTimestamp = java.time.Instant.now();

        AuditEvent event1 = AuditEvent.builder()
            .eventType("USER_LOGIN")
            .actorId("actor-1")
            .resourceType("USER")
            .resourceId("resource-1")
            .payload("{\"details\": \"test\"}")
            .serverTimestamp(fixedTimestamp)
            .clientTimestamp(null)
            .build();

        auditEventHasher.computeHash(event1, null);
        String hash1 = event1.getContentHash();

        // Same event but with a clientTimestamp should produce identical contentHash
        AuditEvent event2 = AuditEvent.builder()
            .eventType("USER_LOGIN")
            .actorId("actor-1")
            .resourceType("USER")
            .resourceId("resource-1")
            .payload("{\"details\": \"test\"}")
            .serverTimestamp(fixedTimestamp)
            .clientTimestamp(java.time.Instant.now())
            .build();

        auditEventHasher.computeHash(event2, null);
        String hash2 = event2.getContentHash();

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void testMultipleEventsFormValidHashChain() {
        AuditEvent event1 = AuditEvent.builder()
            .eventType("EVENT_1")
            .actorId("actor-1")
            .resourceType("RESOURCE")
            .resourceId("res-1")
            .payload("{\"data\": \"1\"}")
            .build();
        auditEventHasher.computeHash(event1, null);

        AuditEvent event2 = AuditEvent.builder()
            .eventType("EVENT_2")
            .actorId("actor-1")
            .resourceType("RESOURCE")
            .resourceId("res-2")
            .payload("{\"data\": \"2\"}")
            .build();
        auditEventHasher.computeHash(event2, event1);

        AuditEvent event3 = AuditEvent.builder()
            .eventType("EVENT_3")
            .actorId("actor-1")
            .resourceType("RESOURCE")
            .resourceId("res-3")
            .payload("{\"data\": \"3\"}")
            .build();
        auditEventHasher.computeHash(event3, event2);

        // Verify chain integrity
        assertThat(event1.getPreviousHash())
            .isEqualTo("0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(event2.getPreviousHash()).isEqualTo(event1.getRecordHash());
        assertThat(event3.getPreviousHash()).isEqualTo(event2.getRecordHash());
    }

    private String computeSha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}

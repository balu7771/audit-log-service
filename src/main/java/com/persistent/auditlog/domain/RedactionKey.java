package com.persistent.auditlog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Per-field decryption key for a crypto-shredded payload field. Redaction is
 * implemented by hard-deleting the row for a (sequenceId, fieldPath) pair -
 * this table intentionally has no immutability trigger, unlike audit_events.
 */
@Entity
@Table(name = "redaction_keys")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RedactionKey {

    @EmbeddedId
    private RedactionKeyId id;

    @Column(name = "encryption_key", nullable = false)
    private byte[] encryptionKey;

    @Column(name = "iv", nullable = false)
    private byte[] iv;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

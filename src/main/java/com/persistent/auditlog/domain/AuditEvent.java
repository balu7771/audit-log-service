package com.persistent.auditlog.domain;

import com.persistent.auditlog.config.JsonbType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.Instant;

@Entity
@Table(name = "audit_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sequence_id")
    private Long sequenceId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @Column(name = "payload", nullable = false)
    @Type(JsonbType.class)
    private String payload;

    @Column(name = "server_timestamp", nullable = false)
    private Instant serverTimestamp;

    @Column(name = "client_timestamp")
    private Instant clientTimestamp;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "record_hash", nullable = false, length = 64)
    private String recordHash;

    @Column(name = "previous_hash", nullable = false, length = 64)
    private String previousHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (serverTimestamp == null) {
            serverTimestamp = Instant.now();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

# Audit Log Service — C4 Architecture Diagrams

Reflects the system **as currently implemented** (Scenario A only — write API,
query API, hash-chain verification). Scenario B (retention/redaction/export)
and Scenario C (compliance reporting) are not yet built; see
[`PRD-COMPLIANCE.md`](./PRD-COMPLIANCE.md) for the gap analysis.

Rendered natively by GitHub. To view/edit interactively, paste any block into
https://mermaid.live.

## Level 1 — System Context

```mermaid
C4Context
    title System Context — Audit Log Service

    Person(reviewer, "Auditor / Reviewer", "Queries events and checks chain integrity via the API")
    System_Ext(sourceSystems, "Business Systems", "Auth, account, permissions, etc. — the systems whose actions get recorded (out of scope for this exercise; simulated via direct API calls)")

    System(als, "Audit Log Service", "Records an append-only, tamper-evident history of events; exposes write, query, and chain-verification APIs")

    Rel(sourceSystems, als, "Writes audit events", "HTTPS/JSON — POST /audit/events")
    Rel(reviewer, als, "Queries events, verifies chain", "HTTPS/JSON — GET /audit/events, GET /audit/events/verify")
```

## Level 2 — Containers

```mermaid
C4Container
    title Container Diagram — Audit Log Service

    Person(reviewer, "Auditor / Reviewer")

    System_Boundary(als, "Audit Log Service (docker-compose)") {
        Container(api, "Spring Boot Application", "Java 21, Spring Boot 3.5, Spring Data JPA", "REST API for writing/querying audit events and verifying the hash chain. Computes SHA-256 hashes on write; recomputes and compares on verify.")
        ContainerDb(db, "PostgreSQL 16", "Relational DB (Flyway-migrated)", "audit_events table: JSONB payload, contentHash/recordHash/previousHash columns. A BEFORE UPDATE/DELETE trigger unconditionally rejects mutation as a defense-in-depth layer beneath the API.")
    }

    Rel(reviewer, api, "REST calls", "HTTP/JSON, port 8080")
    Rel(api, db, "Reads/writes audit_events", "JDBC (Hibernate)")
    Rel(db, api, "Raises exception on UPDATE/DELETE", "Trigger")
```

## Level 3 — Components (inside the Spring Boot Application)

```mermaid
C4Component
    title Component Diagram — Spring Boot Application

    Container_Boundary(api, "Spring Boot Application") {
        Component(controller, "AuditEventController", "@RestController", "POST /audit/events, GET /audit/events, GET /audit/events/verify. Returns 405 for PUT/PATCH/DELETE on the events resource.")
        Component(eventService, "AuditEventService", "@Service", "Builds a new AuditEvent, looks up the last record for chain linkage, delegates hashing, persists.")
        Component(queryService, "AuditEventQueryService", "@Service", "Builds a composable JPA Specification from actorId/resourceType/resourceId/eventType/from/to and paginates.")
        Component(verifyService, "AuditEventChainVerificationService", "@Service", "Walks the full chain (or last N), recomputes each contentHash/recordHash, and reports the first violation found — sequence gap, content/record/previous-hash mismatch.")
        Component(hasher, "AuditEventHasher", "@Component", "Canonical (fixed-field-order) JSON serialization + SHA-256 for contentHash and recordHash = SHA256(contentHash + previousHash). Defines the genesis previousHash constant.")
        Component(failureLogger, "AuditEventFailureLogger", "@Service", "Writes validation failures and chain violations back into audit_events as AUDIT_EVENT_WRITE_FAILURE / AUDIT_CHAIN_VIOLATION events, self-referentially auditing the audit log.")
        Component(repository, "AuditEventRepository", "Spring Data JPA + JpaSpecificationExecutor", "Persistence access to audit_events. No update/delete methods are exposed.")
    }

    ContainerDb(db, "PostgreSQL 16", "audit_events + immutability trigger")

    Rel(controller, eventService, "createAuditEvent(request)")
    Rel(controller, queryService, "queryAuditEvents(filters, page)")
    Rel(controller, verifyService, "verifyChain(lastN)")
    Rel(controller, failureLogger, "logValidationFailure() on 400")
    Rel(eventService, hasher, "computeHash(event, previous)")
    Rel(eventService, repository, "findAll() / save()")
    Rel(verifyService, hasher, "recompute contentHash for comparison")
    Rel(verifyService, repository, "findAll(sorted by sequenceId)")
    Rel(verifyService, failureLogger, "log CHAIN_VIOLATION")
    Rel(failureLogger, repository, "save() failure/violation event")
    Rel(queryService, repository, "findAll(spec, pageable)")
    Rel(repository, db, "Hibernate / JDBC")
```

## Key structural decisions visible in the diagrams

- **Two independent immutability layers**: the API layer (405 on
  PUT/PATCH/DELETE) and the database layer (trigger rejecting UPDATE/DELETE
  unconditionally, including direct SQL). Tests exercise tamper-detection by
  disabling the trigger first (`ALTER TABLE ... DISABLE TRIGGER`) — see the
  gap analysis for why this matters for manual validation.
- **Hash chain is two-hash, not one**: `contentHash` covers only the event's
  own fields (tamper-detects the record itself); `recordHash` folds in
  `previousHash` (tamper-detects chain position/ordering). Verification checks
  both independently, which is how it can distinguish `CONTENT_HASH_MISMATCH`
  from `PREVIOUS_HASH_MISMATCH`/`SEQUENCE_GAP`.
- **`AuditEventFailureLogger` writes into the same table it's protecting** —
  validation failures and chain violations become audit events themselves,
  chained like any other record.

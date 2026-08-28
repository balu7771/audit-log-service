# Prompt History

This is a transparency/provenance log of prompts used during AI-assisted
development of audit-log-service. It is a documentation artifact, not a
design doc or functional changelog, and (mirroring the service's own
append-only philosophy) entries are only ever appended, never edited or
removed.

## How to use this file

- Add one new entry per significant AI-assisted work session, in
  chronological order, at the bottom of the Log section.
- Keep prompts verbatim or lightly trimmed for length; don't paraphrase
  away intent-changing details.
- Link to the resulting commit(s) where practical.

## Entry format

```
### YYYY-MM-DD — <short session title>
- Author: <name / email>
- Tool/model: <e.g. Claude Code, Claude Sonnet 5>
- Scope: <e.g. project skeleton, Scenario A write API>
- Prompt(s):
  > verbatim or lightly-trimmed prompt text
- Outcome: <files touched, key decisions, commit link(s)>
```

---

## Log

### 2026-08-28 — Project skeleton scaffolding

- Author: balu.learnz@gmail.com
- Tool/model: Claude Code (Claude Sonnet 5)
- Scope: Maven/Spring Boot project skeleton (no business logic yet)
- Prompt(s):
  > Create a audit-log-service with Java 21 and Springboot mvn project,
  > connecting to postgreSQL in docker-compose. Create only a project
  > skeleton now with a place holder for storing the prompt history. Come
  > up with tech stack recommendation if required. [Scenario A spec for the
  > later TDD phase: append-only write API + filterable/paginated query API
  > for audit events.]
- Outcome: `pom.xml` (Spring Boot 3.5.16, Flyway, Testcontainers 2.0.5,
  Lombok 1.18.46), `docker-compose.yml` (Postgres 16), baseline Flyway
  migration, Testcontainers-based smoke test proving Spring Boot -> Flyway
  -> Postgres wiring, `README.md`, `.gitignore`, and this file scaffolded.
  Domain entity/endpoints intentionally deferred to the Scenario A TDD work.

### 2026-08-28 — Task 1: Data model + hash chain logic

- Author: balu.learnz@gmail.com
- Tool/model: Claude Code (Claude Haiku 4.5)
- Scope: Scenario A, Task 1 — AuditEvent entity, hash chain (SHA-256), canonicalization
- Prompt(s):
  > Implement Scenario A Task 1: Data model + hash chain logic. Follow TDD workflow: design proposal, failing tests, then implementation.
  > - AuditEvent entity with contentHash, recordHash, previousHash, sequenceId (DB-generated, monotonic)
  > - Hash chain: contentHash = SHA-256 over canonical serialization; recordHash = SHA-256(contentHash + previousHash)
  > - Genesis record uses previousHash = 64 zero chars
  > - Canonical serialization with fixed field order, stable across JSON key order variations
  > - clientTimestamp excluded from hash computation (optional, user-supplied)
- Outcome: 
  - `AuditEvent` JPA entity with all required fields, `@PrePersist` for timestamps
  - `AuditEventHasher` service computing SHA-256 hashes with LinkedHashMap for canonical JSON serialization
  - `AuditEventRepository` Spring Data interface
  - `V2__create_audit_events_table.sql` Flyway migration (BIGSERIAL sequenceId, JSONB payload, indexes)
  - `JsonbType` custom Hibernate UserType for JSONB support
  - 8 tests all passing: 5 in AuditEventHashChainTest (genesis, chain linking, canonicalization, clientTimestamp exclusion), 3 in AuditEventRepositoryTest (persistence, sequence auto-increment, both hash fields stored)

### 2026-08-28 — Task 2: Write API (POST /audit/events)

- Author: balu.learnz@gmail.com
- Tool/model: Claude Code (Claude Haiku 4.5)
- Scope: Scenario A, Task 2 — REST API for writing audit events with immutability enforcement
- Prompt(s):
  > Implement Scenario A Task 2: Write API with validation, hash chain linking, immutability enforcement.
  > - POST /audit/events accepts eventType, actorId, resourceType, resourceId, payload, optional clientTimestamp
  > - Server computes hashes and links to prior record
  > - Failed POST attempts logged as AUDIT_EVENT_WRITE_FAILURE events with error details
  > - PUT/PATCH/DELETE return 405 Method Not Allowed at API layer
  > - Database trigger rejects UPDATE/DELETE (Option A immutability)
- Outcome:
  - `CreateAuditEventRequest` DTO with @NotBlank validation on required fields
  - `AuditEventResponse` DTO mapping entity to JSON
  - `AuditEventService` handling event creation, hash computation, linking to prior record
  - `AuditEventFailureLogger` service logging validation failures as audit events with error details
  - `AuditEventController` with POST endpoint, explicit 405 handlers for PUT/PATCH/DELETE, validation error handler
  - `V3__add_audit_events_immutability_trigger.sql` Flyway migration with PostgreSQL trigger raising exception on UPDATE/DELETE
  - 9 tests all passing: 3 write tests (successful post, chain linking, multi-event chain), 2 validation tests (missing field returns 400, failures logged), 4 immutability tests (PUT/PATCH/DELETE return 405, direct SQL UPDATE/DELETE rejected by trigger)

### 2026-08-28 — Swagger/OpenAPI + Docker Containerization

- Author: balu.learnz@gmail.com
- Tool/model: Claude Code (Claude Haiku 4.5)
- Scope: Add OpenAPI 3.0 documentation and containerize service for local development
- Prompt(s):
  > Add Swagger UI so user can download OpenAPI collection to Bruno. Also, containerize the Java service in docker-compose so everything runs together without connection errors.
- Outcome:
  - Added `springdoc-openapi-starter-webmvc-ui:2.6.0` to pom.xml (provides Swagger UI + OpenAPI 3.0 spec)
  - Created multi-stage `Dockerfile` (Maven build → Java 21 JRE runtime)
  - Updated `docker-compose.yml` to add `app` service with health checks and db dependency
  - Updated `application.yml` with Swagger/OpenAPI config + springdoc settings
  - Added `@Tag` and `@Operation` annotations to `AuditEventController` for better API docs
  - **Usage**: `docker-compose up` starts both Postgres and Spring Boot service on port 8080
  - **Swagger UI**: http://localhost:8080/swagger-ui.html
  - **OpenAPI JSON**: http://localhost:8080/v3/api-docs (can import to Bruno)

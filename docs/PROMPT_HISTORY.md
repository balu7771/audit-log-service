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

### 2026-08-28 — Task 3: Query API (GET /audit/events)

- Author: balu.learnz@gmail.com
- Tool/model: Claude Code (Claude Haiku 4.5)
- Scope: Scenario A, Task 3 — Filterable query API with pagination
- Prompt(s):
  > Implement Scenario A Task 3: Query API with filtering and pagination. Follow TDD workflow.
  > - GET /audit/events with optional filters: actorId, resourceType, resourceId, eventType, from/to timestamps
  > - Page-based pagination (page, size; default page=0, size=20, max=100)
  > - Default sort: sequenceId DESC (newest first)
  > - Validation: resourceId requires resourceType, page size ≤ 100
- Outcome:
  - Extended `AuditEventRepository` with `JpaSpecificationExecutor<AuditEvent>` for dynamic queries
  - `AuditEventQueryService` with Specifications builder for composable AND-logic filtering
  - `PaginatedAuditEventsResponse` DTO with `PageableInfo` for paginated results
  - GET endpoint in `AuditEventController` accepting all query params
  - Exception handler for `IllegalArgumentException` (validation errors) → 400 Bad Request
  - 11 tests all passing: default pagination, filter alone (actorId, eventType, resourceType+resourceId, time range), combined filters, pagination accuracy (page/size/total), ordering (DESC), empty results, validation (resourceId without resourceType, page size > 100)

### 2026-08-28 — Task 4: Chain verification API (GET /audit/events/verify)

- Author: balu.learnz@gmail.com
- Tool/model: Claude Code (Claude Sonnet 5)
- Scope: Scenario A, Task 4 — verify chain integrity, `AuditChainVerificationTest` was already scaffolded (10 tests) and mostly failing
- Prompt(s):
  > lets proceed with completing Task 4, the verify chain endpoint. Check the test cases before doing. Most of them already created. Needs completion and prompt addition.
- Outcome:
  - Fixed `AuditEventHasher.computeHash`: `serverTimestamp` was being hashed while still `null` (it was only populated later by `@PrePersist`), so every stored `contentHash` was unreproducible on verification — now `serverTimestamp` is fixed before hashing.
  - Rewrote `AuditEventChainVerificationService.verifyChain`: fixed an `IndexOutOfBoundsException` when verifying the first record of a `lastN` window (was indexing into the windowed sublist instead of the full list for the prior record), and collapsed the four near-duplicate violation-building blocks into one `recordViolation` helper. Removed unused `computeSha256` method.
  - Fixed 3 wrong test URLs in `AuditChainVerificationTest` (`/audit/verify` → `/audit/events/verify`).
  - Fixed 2 stale-by-accident assertions in `AuditEventHashChainTest` (`testCanonicalSerializationIsStable`, `testClientTimestampExcludedFromContentHash`): they compared hashes of two independently-built events and only passed because both previously hashed against a `null` serverTimestamp; now pinned to an explicit shared `serverTimestamp` so they test what they claim to.
  - Fixed a latent Testcontainers bug in `AbstractIntegrationTest`: the static `@Container` field, inherited by every integration test class, was stopped by JUnit's `@Testcontainers` extension after the first test class ran, breaking every class after it when running the full suite (`Connection refused`). Switched to the singleton-container pattern (start once in a static block, register connection properties via `@DynamicPropertySource`, no `@Testcontainers`/`@Container` lifecycle management).
  - All 38 tests across 5 test classes pass via `./mvnw test`.

### 2026-08-28 — PRD gap analysis + fixes: verify path, README, API-key auth

- Author: balu.learnz@gmail.com
- Tool/model: Claude Code (Claude Sonnet 5)
- Scope: Compare implementation against PRD §5 Scenario A, produce C4
  diagrams + gap analysis, then fix the path deviation, stale README, and
  missing-auth gaps the analysis surfaced.
- Prompt(s):
  > given the PRD at @docs/prd/... , particularly for the scenario A,
  > described in section 5. Scan the code of audit-log-service. Compare
  > what's actually built against the PRD and give your findings.
  > Assumptions and trade-offs done if any. Also produce a C4 architecture
  > diagram and store within docs directory in the repo.
  > can you fix point 2, 4 and 6. Also, create a document within
  > architecture for the tradeoffs and assumptions.
- Outcome:
  - Wrote `docs/architecture/C4-diagram.md` (Context/Container/Component
    Mermaid diagrams) and `docs/architecture/PRD-COMPLIANCE.md`
    (requirement-by-requirement gap analysis) reviewing the code as it stood
    (commit `2295111`).
  - Gap analysis found: verify endpoint at `/audit/events/verify` instead of
    the PRD's literal `GET /audit/verify`; `README.md` still describing the
    project as an unimplemented skeleton; no authentication on any endpoint;
    malformed-JSON `payload` surfacing as an unhandled 500; and the
    immutability trigger blocking the PRD's own "tamper directly in the data
    store" validation step unless disabled first (undocumented).
  - Fixed the path deviation: added `AuditChainController` at `/audit`
    exposing `GET /audit/verify`; removed the old nested mapping from
    `AuditEventController`.
  - Fixed the stale README: rewritten with current API surface, the new
    auth requirement, and a manual validation walkthrough that documents the
    `ALTER TABLE ... DISABLE/ENABLE TRIGGER` step needed to actually exercise
    tamper detection against the immutability trigger.
  - Added a lightweight `ApiKeyAuthFilter` (shared-secret `X-API-Key` header,
    checked against `audit.security.api-key`, default overridable via
    `AUDIT_API_KEY`) gating all `/audit/**` requests; actuator/Swagger stay
    open. Added `authenticatedMockMvc()` helper to `AbstractIntegrationTest`
    so existing tests didn't need per-call header changes, plus a new
    `ApiKeyAuthFilterTest` (4 tests) covering missing/wrong/correct key and
    that actuator health stays ungated.
  - Wrote `docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md` as the dedicated
    assumptions/trade-offs doc, and trimmed the duplicated content out of
    `PRD-COMPLIANCE.md` in favor of pointing to it.
  - All 42 tests across 7 test classes pass via `./mvnw verify`.

### 2026-08-28 — Scenario B: retention, structured redaction, bulk export

- Author: balu.learnz@gmail.com
- Tool/model: Claude Code (Claude Sonnet 5)
- Scope: Scenario B — extend the Scenario A hash-chained audit log with
  retention/archival, structured redaction of sensitive payload fields, and a
  bulk export endpoint, per PRD §5 Scenario B.
- Prompt(s):
  > Let's move on to Scenario B: Retention Policy (records older than a
  > configurable window should be archivable or soft-deletable; the chain
  > verification endpoint must handle archived records correctly, no false
  > positive). Structured Redaction (sensitive payload fields must be
  > redactable to satisfy data privacy requirements, without breaking the
  > hash chain — design and implement a redaction scheme, document approach/
  > trade-offs/limitations). Bulk Export (export all records for a given
  > resourceId or actorId as a self-contained, verifiable bundle, with enough
  > chain metadata for independent verification). Ask in case of any
  > ambiguity, don't assume.
- Design decisions confirmed with the requester before implementation (see
  `ASSUMPTIONS-AND-TRADEOFFS.md` for the full rationale and rejected
  alternatives):
  - Redaction: **crypto-shredding** (encrypt declared-sensitive fields with
    AES-256-GCM *before* hashing; redact by destroying the per-field key in
    a separate table) over a salted hash-commitment scheme — chosen because
    it requires zero changes to `AuditEventHasher` and zero exceptions to
    the immutability trigger for the redaction path itself.
  - Retention: **soft-delete flag on the same table** (`archived_at`,
    outside the hash) over physically moving/deleting rows — chosen because
    it requires no archive-aware gap-handling logic in the verifier at all.
  - Bulk export: **per-record hash + manifest hash + HMAC signature** over
    manifest-hash-only, so a recipient can detect per-record tampering,
    record add/remove/reorder, and wholesale bundle fabrication as three
    distinct, independently-checkable guarantees.
  - Sensitive fields are declared by the caller per-event at write time
    (`sensitiveFields` on `CreateAuditEventRequest`), not a fixed global list.
- Outcome:
  - Migrations `V4` (`sensitive_fields` column + `redaction_keys` table, no
    trigger on the latter), `V5` (`archived_at` column + partial index), `V6`
    (narrows the V3 immutability trigger to allow exactly one transition:
    `archived_at` NULL -> non-null with every other column byte-identical).
  - New: `RedactionKey`/`RedactionKeyId` entities, `FieldEncryptor`
    (AES-256-GCM), `HmacSigner` (HMAC-SHA256), `PayloadRedactionService`
    (encrypt-at-write, decrypt-or-`[REDACTED]`-at-read), `RedactionService`
    (destroys keys, self-logs a `FIELD_REDACTED` audit event),
    `AuditEventRetentionService` (native bulk-UPDATE archival, avoiding
    entity-`save()` precision-drift risk against the new trigger),
    `AuditExportService` (bundle build + independent re-verification),
    `RedactionKeyRepository`, new `archiveEligible`/`countByArchivedAtIsNotNull`
    on `AuditEventRepository`.
  - New controllers: `RedactionController` (`POST /audit/events/{id}/redactions`),
    `AuditRetentionController` (`POST /audit/retention/archive`),
    `AuditExportController` (`GET /audit/export`, `POST /audit/export/verify`).
    Extracted `GlobalExceptionHandler` (`@RestControllerAdvice`) from
    `AuditEventController` so validation/`IllegalArgumentException` handling
    is shared across all `/audit/**` controllers, not duplicated per class.
  - Modified: `AuditEventService` (encrypts sensitive fields before hashing,
    persists event + redaction keys in one transaction), `AuditEventHasher`
    (extracted `recomputeContentHashOnly` as a public method, reused by both
    chain verification and export-bundle verification — its actual hashing
    logic is otherwise untouched), `AuditEventChainVerificationService`
    (reports `archivedRecordsCount`, no other logic changes),
    `AuditEventQueryService`/`AuditEventController` (`includeArchived`
    filter, decrypt-or-placeholder rendering on read),
    `CreateAuditEventRequest`/`AuditEventResponse`/`VerificationResponse`
    (new fields).
  - Fixed a consequence of the new `redaction_keys` FK: every existing
    test's `TRUNCATE TABLE audit_events RESTART IDENTITY` needed `CASCADE`
    added, since Postgres blocks truncating a table with an incoming FK
    reference regardless of whether the referencing table currently has
    rows.
  - New tests: `FieldEncryptorTest`, `RedactionKeyRepositoryTest`,
    `AuditEventRedactionIT`, `AuditEventRetentionIT`, `AuditExportTest`, plus
    two new cases in `AuditChainVerificationTest` for archived-record
    handling. 74 tests across 12 test classes pass via `./mvnw verify`
    (60 unit/component + 14 integration).
  - Updated `docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md` (new Scenario B
    section: redaction scheme comparison + limitations, retention design +
    limitations, export verifiability + limitations),
    `docs/architecture/C4-diagram.md` (new components/relationships),
    `docs/architecture/PRD-COMPLIANCE.md` (new Scenario B requirement table),
    and `README.md` (API table, Scenario B manual validation walkthrough,
    updated test count).

# audit-log-service

A tamper-evident, append-only audit event log. Every record is chained to the
one before it via SHA-256 hashes, so any modification made outside the write
API — including a direct SQL edit — is detectable via a chain-verification
endpoint.

This covers **Scenario A** (write API, query API, hash chain, chain
verification) and **Scenario B** (retention/archival, structured redaction,
bulk export) of the assignment (`docs/prd/`). **Scenario C** (compliance
reporting) has a clarified requirement and technical design — see
[`docs/FeatureRequest/scenarioC.md`](docs/FeatureRequest/scenarioC.md) — but
is not implemented yet; see
[`docs/architecture/PRD-COMPLIANCE.md`](docs/architecture/PRD-COMPLIANCE.md)
for the full gap analysis against the PRD.

## Tech stack

| Concern              | Choice                          |
|-----------------------|----------------------------------|
| Language              | Java 21                         |
| Framework             | Spring Boot 3.5.x               |
| Build tool            | Maven (wrapper included)        |
| Database              | PostgreSQL 16 (via Docker Compose) |
| Schema migrations     | Flyway                          |
| Integration testing   | Testcontainers                  |
| API docs              | springdoc-openapi / Swagger UI   |
| Boilerplate reduction | Lombok                          |

## Prerequisites

- JDK 21
- Docker + Docker Compose (no local Maven install required — use `./mvnw`)

## Running locally

```bash
docker compose up -d        # starts PostgreSQL (+ optionally the app) on localhost:5432/8080
./mvnw spring-boot:run       # or run the app directly on localhost:8080
curl localhost:8080/actuator/health
```

Or run everything, including the app, inside Docker:

```bash
docker compose up --build
```

### Authentication and RBAC

Every `/audit/**` request requires an `X-API-Key` header (see
[Assumptions & Trade-offs](docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md) for
why this is a shared-secret-per-role gate rather than full identity-aware
auth). Each configured key is scoped to one of three roles
(`audit.security.api-keys` in `application.yml`), so a regular event-producing
caller and a compliance auditor are no longer the same all-access token:

| Role | Can call | Local default (env var to override) |
|---|---|---|
| `WRITER` | `POST /audit/events` | `local-dev-writer-key-change-me` (`AUDIT_API_KEY_WRITER`) |
| `AUDITOR` | `GET /audit/events`, `GET /audit/verify`, `GET /audit/export`, `POST /audit/export/verify` | `local-dev-auditor-key-change-me` (`AUDIT_API_KEY_AUDITOR`) |
| `ADMIN` | `POST /audit/retention/archive`, `POST /audit/events/{id}/redactions` — plus everything `AUDITOR` and `WRITER` can (role hierarchy) | `local-dev-admin-key-change-me` (`AUDIT_API_KEY_ADMIN`) |

`docker compose` sets `local-compose-{writer,auditor,admin}-key-change-me`. A
request with a valid key but the wrong role gets `403 Forbidden`; a
missing/unknown key gets `401 Unauthorized`. `/actuator/**` and the Swagger UI
are not gated.

## API surface

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/audit/events` | Append a new audit event |
| `GET`  | `/audit/events` | Query events, filterable by `actorId`, `resourceType`+`resourceId`, `eventType`, `from`/`to`, paginated via `page`/`size` |
| `GET`  | `/audit/verify` | Walk the hash chain (or last `lastN` records) and report whether it's intact; also reports `archivedRecordsCount` |
| `PUT` / `PATCH` / `DELETE` | `/audit/events/{id}` | Explicitly disabled — `405 Method Not Allowed` (records are immutable) |
| `POST` | `/audit/events/{sequenceId}/redactions` | Redact a field declared `sensitiveFields` at creation time — destroys its decryption key, without touching the hash chain |
| `POST` | `/audit/retention/archive` | Soft-archive (`archived_at`) records older than the retention window (default from `audit.retention.window-days`, overridable via `windowDays`) |
| `GET`  | `/audit/export` | Export all records for a given `resourceType`+`resourceId` **or** `actorId` (mutually exclusive) as a self-contained, hash-and-signature verifiable bundle |
| `POST` | `/audit/export/verify` | Independently re-verify a previously exported bundle (per-record hashes, manifest hash, HMAC signature) |

Full request/response schemas: `http://localhost:8080/swagger-ui.html` (or
`/v3/api-docs` for the raw OpenAPI JSON — importable into Bruno/Postman).

`timestamp` handling: the server always assigns and hashes `serverTimestamp`
(authoritative — used for chain integrity and time-range queries). Callers
may optionally also supply `clientTimestamp`, which is stored for reference
but excluded from the hash (a caller can't claim an arbitrary event time and
have it treated as tamper-evident).

## Manual validation walkthrough

This mirrors the PRD's acceptance flow: write → query → verify → tamper
directly in the data store → verify again to confirm detection.

```bash
API=localhost:8080
WRITER_KEY="local-dev-writer-key-change-me"    # or your AUDIT_API_KEY_WRITER override
AUDITOR_KEY="local-dev-auditor-key-change-me"  # or your AUDIT_API_KEY_AUDITOR override

# 1. Write a couple of events (WRITER role)
curl -s -X POST $API/audit/events -H "X-API-Key: $WRITER_KEY" -H "Content-Type: application/json" \
  -d '{"eventType":"USER_LOGIN","actorId":"user-1","resourceType":"USER","resourceId":"user-1","payload":"{\"ip\":\"10.0.0.1\"}"}'
curl -s -X POST $API/audit/events -H "X-API-Key: $WRITER_KEY" -H "Content-Type: application/json" \
  -d '{"eventType":"DATA_READ","actorId":"user-1","resourceType":"DOCUMENT","resourceId":"doc-1","payload":"{}"}'

# 2. Query them back (AUDITOR role — WRITER_KEY would get 403 here)
curl -s "$API/audit/events?actorId=user-1" -H "X-API-Key: $AUDITOR_KEY"

# 3. Verify the chain is intact (AUDITOR role)
curl -s "$API/audit/verify" -H "X-API-Key: $AUDITOR_KEY"   # -> "intact": true

# 4. Tamper directly in the data store.
#    The audit_events table has a BEFORE UPDATE/DELETE trigger that rejects
#    any direct mutation (the immutability guarantee applies even to a
#    superuser DB session, not just the API) — so to actually exercise the
#    tamper-detection path you must disable it first, tamper, then re-enable:
docker compose exec postgres psql -U auditlog -d auditlog -c \
  "ALTER TABLE audit_events DISABLE TRIGGER audit_events_immutable_trigger;
   UPDATE audit_events SET payload = '{\"ip\":\"1.2.3.4\"}' WHERE sequence_id = 1;
   ALTER TABLE audit_events ENABLE TRIGGER audit_events_immutable_trigger;"

# 5. Verify again — should report intact:false with the sequence_id and
#    violation type (CONTENT_HASH_MISMATCH here) of the first inconsistency
curl -s "$API/audit/verify" -H "X-API-Key: $AUDITOR_KEY"
```

If you skip step 4's `DISABLE TRIGGER`/`ENABLE TRIGGER` and try the `UPDATE`
directly, Postgres rejects it outright with `audit_events table is immutable`
— that's the trigger working as intended, not a bug; it's just a different
(stronger) guarantee than "mutation succeeds but is later detected."

## Manual validation walkthrough — Scenario B (retention, redaction, export)

```bash
API=localhost:8080
WRITER_KEY="local-dev-writer-key-change-me"    # or your AUDIT_API_KEY_WRITER override
AUDITOR_KEY="local-dev-auditor-key-change-me"  # or your AUDIT_API_KEY_AUDITOR override
ADMIN_KEY="local-dev-admin-key-change-me"      # or your AUDIT_API_KEY_ADMIN override

# 1. Write an event with a declared-sensitive field (WRITER role)
curl -s -X POST $API/audit/events -H "X-API-Key: $WRITER_KEY" -H "Content-Type: application/json" \
  -d '{"eventType":"USER_UPDATED","actorId":"actor-1","resourceType":"USER","resourceId":"user-1",
       "payload":"{\"ssn\":\"123-45-6789\",\"name\":\"Jane\"}","sensitiveFields":["ssn"]}'
# -> response payload shows the decrypted ssn; the DB row stores an AES-256-GCM ciphertext envelope instead

# 2. Query it back — still decrypted, because the key hasn't been destroyed yet (AUDITOR role)
curl -s "$API/audit/events?resourceType=USER&resourceId=user-1" -H "X-API-Key: $AUDITOR_KEY"

# 3. Redact the field — permanently destroys its decryption key (ADMIN role; AUDITOR_KEY gets 403 here)
curl -s -X POST $API/audit/events/1/redactions -H "X-API-Key: $ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"fieldPath":"ssn","actorId":"compliance-officer","reason":"privacy request"}'

# 4. Query again — the field now shows "[REDACTED]"; content_hash/record_hash for
#    sequence_id=1 are byte-identical to before redaction (redaction never touched audit_events)
curl -s "$API/audit/events?resourceType=USER&resourceId=user-1" -H "X-API-Key: $AUDITOR_KEY"

# 5. Verify — still intact, because redaction never touched the hash chain
curl -s "$API/audit/verify" -H "X-API-Key: $AUDITOR_KEY"   # -> "intact": true

# 6. Archive records older than a window (0 days archives everything eligible, for a quick demo) — ADMIN role
curl -s -X POST "$API/audit/retention/archive?windowDays=0" -H "X-API-Key: $ADMIN_KEY"

# 7. Verify again — still intact, and now reports archivedRecordsCount > 0
curl -s "$API/audit/verify" -H "X-API-Key: $AUDITOR_KEY"

# 8. Export everything for the resource as a self-contained, verifiable bundle (AUDITOR role)
curl -s "$API/audit/export?resourceType=USER&resourceId=user-1" -H "X-API-Key: $AUDITOR_KEY" > bundle.json

# 9. Verify the bundle independently — recomputes per-record hashes, the manifest
#    hash, and the HMAC signature
curl -s -X POST $API/audit/export/verify -H "X-API-Key: $AUDITOR_KEY" -H "Content-Type: application/json" \
  -d @bundle.json   # -> "valid": true

# 10. Tamper with the downloaded bundle file (e.g. edit a storedPayload value) and
#     re-run step 9 — "valid" flips to false, with perRecordIntact/manifestIntact/
#     signatureIntact pinpointing which layer caught it.
```

## Running tests

```bash
./mvnw test      # unit tests only — no Docker required
./mvnw verify    # unit + integration tests + coverage gate — requires Docker (Testcontainers)
```

89 tests across 13 test classes cover: hash chain computation/canonicalization,
persistence, the write API, immutability enforcement (both the API's `405`s
and the DB trigger, including its narrowed archival-only exception), query
filters/pagination, all four chain-verification violation types (sequence
gap, content-hash, record-hash, previous-hash mismatch) plus archived-record
handling, the API-key filter, RBAC (each of the three roles against every
endpoint — see `RoleBasedAccessControlTest`), AES-256-GCM field encryption,
redaction (idempotency, unknown-field validation, hash-chain non-impact),
retention window archival, and export-bundle verification (per-record,
manifest, and signature tampering).

### Code coverage and static analysis

`./mvnw verify` runs JaCoCo across both unit and integration tests, merges
the two exec files into one report, and **fails the build** if line coverage
drops below 80% — config classes and request/response DTOs are excluded from
the instrumentation (declarative bindings, not behavior to test). The
HTML report lands at `target/site/jacoco/index.html`.

```bash
./mvnw verify
open target/site/jacoco/index.html   # HTML coverage report
```

For static analysis (SonarQube) and container image scanning (Trivy), see
[Quality gates](docs/architecture/QUALITY-GATES.md).

### Troubleshooting: truncating `audit_events` manually

`redaction_keys` has a foreign key to `audit_events(sequence_id)` (see
`V4__add_redaction_support.sql`) with no `ON DELETE` clause, so a plain

```sql
TRUNCATE TABLE audit_events RESTART IDENTITY;
```

fails with `cannot truncate a table referenced in a foreign key constraint`.
Add `CASCADE` (safe here — `redaction_keys` is the only referencing table):

```sql
TRUNCATE TABLE audit_events RESTART IDENTITY CASCADE;
```

This is the pattern already used in every test's `@BeforeEach` (e.g.
`AuditEventRepositoryTest.java`).

## Project structure

```
src/main/java/com/persistent/auditlog/
├── domain/      # AuditEvent/RedactionKey entities, AuditEventHasher (hash chain logic)
├── api/         # REST controllers, request/response DTOs
├── service/     # write/query/verify/redact/retain/export use-case services, failure logging
├── repository/  # Spring Data repositories
├── crypto/      # FieldEncryptor (AES-256-GCM), HmacSigner (export bundle signing)
└── config/      # JSONB column mapping, API-key auth filter
```

## Architecture & design decisions

See [`docs/architecture/C4-diagram.md`](docs/architecture/C4-diagram.md) for
Context/Container/Component diagrams and
[`docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md`](docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md)
for the assumptions and trade-offs made building this. For Scenario C
(compliance reporting), see
[`docs/FeatureRequest/scenarioC.md`](docs/FeatureRequest/scenarioC.md) for
the requirement clarification, identified ambiguities, and technical design
— design only, not yet implemented.

## Prompt history

This repository keeps an append-only log of the prompts used during
AI-assisted development, for transparency: see
[`docs/PROMPT_HISTORY.md`](docs/PROMPT_HISTORY.md).

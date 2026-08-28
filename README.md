# audit-log-service

A tamper-evident, append-only audit event log. Every record is chained to the
one before it via SHA-256 hashes, so any modification made outside the write
API — including a direct SQL edit — is detectable via a chain-verification
endpoint.

This is **Scenario A** of the assignment (`docs/prd/`): write API, query API,
hash chain, chain verification. Scenario B (retention/redaction/export) and
Scenario C (compliance reporting) are not implemented yet — see
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

### Authentication

Every `/audit/**` request requires an `X-API-Key` header (see
[Assumptions & Trade-offs](docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md) for
why this is a shared-secret gate rather than full auth). Locally it defaults
to `local-dev-only-key-change-me` (from `application.yml`); override with the
`AUDIT_API_KEY` environment variable for anything beyond local dev. `docker
compose` sets it to `local-compose-key-change-me`. `/actuator/**` and the
Swagger UI are not gated.

## API surface

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/audit/events` | Append a new audit event |
| `GET`  | `/audit/events` | Query events, filterable by `actorId`, `resourceType`+`resourceId`, `eventType`, `from`/`to`, paginated via `page`/`size` |
| `GET`  | `/audit/verify` | Walk the hash chain (or last `lastN` records) and report whether it's intact |
| `PUT` / `PATCH` / `DELETE` | `/audit/events/{id}` | Explicitly disabled — `405 Method Not Allowed` (records are immutable) |

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
KEY="local-dev-only-key-change-me"   # or your AUDIT_API_KEY override

# 1. Write a couple of events
curl -s -X POST $API/audit/events -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"eventType":"USER_LOGIN","actorId":"user-1","resourceType":"USER","resourceId":"user-1","payload":"{\"ip\":\"10.0.0.1\"}"}'
curl -s -X POST $API/audit/events -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"eventType":"DATA_READ","actorId":"user-1","resourceType":"DOCUMENT","resourceId":"doc-1","payload":"{}"}'

# 2. Query them back
curl -s "$API/audit/events?actorId=user-1" -H "X-API-Key: $KEY"

# 3. Verify the chain is intact
curl -s "$API/audit/verify" -H "X-API-Key: $KEY"   # -> "intact": true

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
curl -s "$API/audit/verify" -H "X-API-Key: $KEY"
```

If you skip step 4's `DISABLE TRIGGER`/`ENABLE TRIGGER` and try the `UPDATE`
directly, Postgres rejects it outright with `audit_events table is immutable`
— that's the trigger working as intended, not a bug; it's just a different
(stronger) guarantee than "mutation succeeds but is later detected."

## Running tests

```bash
./mvnw test      # unit tests only — no Docker required
./mvnw verify    # unit + integration tests — requires Docker (Testcontainers)
```

42 tests across 7 test classes cover: hash chain computation/canonicalization,
persistence, the write API, immutability enforcement (both the API's `405`s
and the DB trigger), query filters/pagination, all four chain-verification
violation types (sequence gap, content-hash, record-hash, previous-hash
mismatch), and the API-key filter.

## Project structure

```
src/main/java/com/persistent/auditlog/
├── domain/      # AuditEvent entity, AuditEventHasher (hash chain logic)
├── api/         # REST controllers, request/response DTOs
├── service/     # write/query/verify use-case services, failure logging
├── repository/  # Spring Data repository
└── config/      # JSONB column mapping, API-key auth filter
```

## Architecture & design decisions

See [`docs/architecture/C4-diagram.md`](docs/architecture/C4-diagram.md) for
Context/Container/Component diagrams and
[`docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md`](docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md)
for the assumptions and trade-offs made building this.

## Prompt history

This repository keeps an append-only log of the prompts used during
AI-assisted development, for transparency: see
[`docs/PROMPT_HISTORY.md`](docs/PROMPT_HISTORY.md).

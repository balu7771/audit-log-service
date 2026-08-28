# audit-log-service

An append-only audit event log service. This repository is currently at the
**project skeleton** stage: build config, database wiring, and test
infrastructure are in place, but the domain model and REST endpoints are not
implemented yet. Those are being built next via TDD ("Scenario A" — see
below).

## Tech stack

| Concern              | Choice                          |
|-----------------------|----------------------------------|
| Language              | Java 21                         |
| Framework             | Spring Boot 3.5.x               |
| Build tool            | Maven (wrapper included)        |
| Database              | PostgreSQL 16 (via Docker Compose) |
| Schema migrations     | Flyway                          |
| Integration testing   | Testcontainers                  |
| Boilerplate reduction | Lombok                          |

## Prerequisites

- JDK 21
- Docker + Docker Compose (no local Maven install required — use `./mvnw`)

## Running locally

```bash
docker compose up -d        # starts PostgreSQL on localhost:5432
./mvnw spring-boot:run       # starts the app on localhost:8080
curl localhost:8080/actuator/health
```

## Running tests

```bash
./mvnw test      # unit tests only — no Docker required
./mvnw verify     # unit + integration tests — requires Docker (Testcontainers)
```

The integration test suite spins up a real PostgreSQL container, runs the
Flyway migrations against it, starts the Spring context, and checks
`/actuator/health` — proving the full stack wiring works end-to-end.

## Project structure

```
src/main/java/com/persistent/auditlog/
├── domain/      # aggregates, value objects
├── api/         # REST controllers, request/response DTOs
├── service/     # application/use-case services
├── repository/  # Spring Data repositories
└── config/      # @Configuration classes
```

## Design decisions status

The append-only write/query API design — including whether `timestamp` is
caller-supplied or server-assigned — is **not yet decided**. It will be
designed and implemented via TDD as part of the upcoming "Scenario A" work.

## Prompt history

This repository keeps an append-only log of the prompts used during
AI-assisted development, for transparency: see
[`docs/PROMPT_HISTORY.md`](docs/PROMPT_HISTORY.md).

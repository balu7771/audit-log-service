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

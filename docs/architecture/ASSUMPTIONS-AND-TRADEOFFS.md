# Assumptions & Trade-offs — Scenario A

This is the canonical list of assumptions made and trade-offs accepted while
building the core audit log service, kept separate from
[`PRD-COMPLIANCE.md`](./PRD-COMPLIANCE.md) (which tracks requirement-by-
requirement status) so it can be read on its own as "why is it built this
way." See [`C4-diagram.md`](./C4-diagram.md) for the structural view these
decisions produced.

## Assumptions

These are choices made where the PRD was silent or only gave an example, not
a strict rule.

1. **`timestamp` is both server-assigned and optionally caller-supplied.**
   `serverTimestamp` is always set by the server, is part of the hashed
   content, and is what queries filter on — it's the authoritative event
   time. Callers may also send `clientTimestamp`, stored for reference but
   *excluded* from the hash, so a caller can't backdate an event and have
   that backdating be tamper-evident. This satisfies the PRD's "caller-
   supplied or server-assigned; document your choice" by doing both for
   different purposes rather than picking one.
2. **`payload` is transported as a JSON-encoded string**, not a nested JSON
   object — `CreateAuditEventRequest.payload` is `String`, so callers send
   `"payload": "{\"ip\":\"1.2.3.4\"}"` rather than a native nested object.
   This simplifies exact-byte canonical hashing (the string is exactly what
   gets stored and re-hashed) at the cost of API ergonomics — the PRD's
   phrasing ("a structured object") suggests a native object was expected.
   Documented here as a deliberate, but debatable, choice.
3. **`resourceId` requires `resourceType`** in the query API (400 if given
   alone). A bare `resourceId` filter is ambiguous across resource types
   (the same ID string could coincidentally exist for two different resource
   types), so this constraint is treated as an implicit correctness
   requirement rather than an arbitrary restriction.
4. **Page size is capped at 100** (`AuditEventQueryService.MAX_PAGE_SIZE`).
   Not specified by the PRD; a standard defensive default against
   accidentally-huge result sets.
5. **`eventType` is a free-form string, not a closed enum.** The PRD gives
   examples (`USER_LOGIN`, `RECORD_UPDATED`, `PERMISSION_GRANTED`) with
   "e.g.," implying an open, extensible set — enforcing an enum would require
   a schema migration every time a new event type is introduced, which
   defeats the purpose of an audit log meant to absorb events from arbitrary
   future producers.

## Trade-offs

1. **Two independent immutability layers — API `405`s and a DB
   `BEFORE UPDATE OR DELETE` trigger.** The API layer alone only stops
   well-behaved API clients; the actual threat model for an audit log
   includes someone with direct database access, which only the trigger
   defends against. Cost: the trigger also blocks the PRD's own suggested
   validation step ("modify a record directly in the data store") unless the
   trigger is disabled first — documented as a manual step in the README's
   validation walkthrough, and worked around in tests via
   `ALTER TABLE ... DISABLE/ENABLE TRIGGER`. This was judged worth it: a
   tamper-evidence guarantee that a DBA can quietly bypass without needing to
   coordinate a trigger toggle would be a weaker guarantee.
2. **Full-chain verification is O(n) per call.**
   `AuditEventChainVerificationService.verifyChain` loads every row
   (`findAll(Sort...)`) before applying the optional `lastN` window — so
   `lastN` reduces client-facing verification work but not query cost; the
   whole table is still fetched. Acceptable for a prototype and the exercise's
   data volumes; a production version would need either a persisted
   "last-verified checkpoint" or a streaming/paged verification strategy to
   avoid loading the full table on every call.
3. **Canonical hashing uses its own freshly-configured `ObjectMapper`**
   inside `AuditEventHasher.toCanonicalJson`, separate from the app-wide
   Jackson bean used elsewhere. This guarantees the hash format can't
   silently drift if the app's main Jackson configuration changes later
   (e.g., a new module or feature flag flipped for API responses) — at the
   cost of a second Jackson configuration that has to be kept in sync by hand
   if the canonicalization rules ever need to change.
4. **`AuditEventFailureLogger` writes validation/chain-violation failures
   back into the same table it's protecting**, as ordinary chained audit
   events (`AUDIT_EVENT_WRITE_FAILURE` / `AUDIT_CHAIN_VIOLATION`). This makes
   audit-of-the-audit-log "free" (same guarantees, no separate sink to build
   or trust), but means a client that retries invalid requests grows the log
   it's supposedly being caught by, and there's no rate limiting or
   separate error channel if that becomes noisy.
5. **Authentication is a single shared API key (`X-API-Key` header,
   `ApiKeyAuthFilter`), not per-caller identity or roles.** A real production
   audit log needs to know *which* caller wrote or read what — but adding
   full authentication/authorization (OAuth/OIDC, per-service credentials,
   RBAC) is a substantial scope increase relative to what this exercise asks
   for ("no external application or consumer is required"). The chosen
   middle ground: gate the API behind one static secret so it's not
   wide open by default, and call out explicitly that this is *not* a
   substitute for real identity-aware auth — `actorId` in a request body is
   still self-reported by the caller and not verified against any
   credential. A production rollout would need to replace this with
   per-caller credentials (so `actorId` can be derived from, or at least
   cross-checked against, an authenticated identity) before this could be
   trusted as a real compliance-grade audit trail.
6. **No validation that `payload` is well-formed JSON before it reaches the
   database.** `AuditEventHasher.parseJsonIfString` falls back to hashing the
   raw string if parsing fails, but the JSONB column still rejects malformed
   JSON at insert time — so a malformed payload currently surfaces as an
   unhandled `500` rather than a clean `400`. Left as a known gap (no test
   covers it) rather than fixed here, since tightening `CreateAuditEventRequest.payload`
   to a validated JSON type is a contained, low-risk follow-up rather than an
   architectural decision.

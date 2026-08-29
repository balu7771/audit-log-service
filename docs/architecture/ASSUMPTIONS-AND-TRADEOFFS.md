# Assumptions & Trade-offs

This is the canonical list of assumptions made and trade-offs accepted while
building the audit log service, kept separate from
[`PRD-COMPLIANCE.md`](./PRD-COMPLIANCE.md) (which tracks requirement-by-
requirement status) so it can be read on its own as "why is it built this
way." See [`C4-diagram.md`](./C4-diagram.md) for the structural view these
decisions produced.

## Scenario A

### Assumptions

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

### Trade-offs

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

## Scenario B — Retention & Redaction

### Structured redaction — the design problem and chosen scheme

The PRD calls this out explicitly as "a genuine engineering problem: the
original hash covers the original value, so simply removing the value would
invalidate the hash." Two schemes were considered:

**Chosen: crypto-shredding.** Fields the caller declares as sensitive
(`CreateAuditEventRequest.sensitiveFields`, a list of top-level payload field
names) are encrypted with a fresh, random AES-256-GCM key and IV
*before* `AuditEventHasher.computeHash` ever runs (`AuditEventService.
createAuditEvent`, `PayloadRedactionService.encryptSensitiveFields`). The
ciphertext envelope (`{"__enc":true,"alg":"AES-256-GCM","iv":...,
"ciphertext":...}`) replaces the plaintext in `payload` at write time, so
`contentHash`/`recordHash` cover ciphertext from the very first write —
**`AuditEventHasher` required zero code changes** for this to work; it just
hashes whatever is in `payload`, as it always did. The per-field key+IV is
stored separately, in a new `redaction_keys` table
(`sequence_id, field_path` → key material), never inside `audit_events`.
Redaction (`RedactionService.redactField`) is then just: hard-delete the key
row. Nothing in `audit_events` is ever touched, so **no immutability-trigger
exception was needed for redaction at all** — the trigger from Scenario A is
untouched by this feature. The read path
(`PayloadRedactionService.renderForRead`/`renderPage`) decrypts ciphertext
back to plaintext when the key still exists, or substitutes `"[REDACTED]"`
when it doesn't.

**Rejected: salted hash-commitment swap.** An alternative considered was
representing sensitive fields in the content-hash input via
`SHA-256(salt + value)` instead of the raw value, then redacting by
overwriting the plaintext while keeping the stored commitment so the hash
still matches. Rejected because: (a) it requires a narrow, hand-maintained
exception to the immutability trigger (the crypto-shredding scheme needs
none), and (b) a bare salted hash of a low-entropy value (a 9-digit SSN, a
4-digit PIN) is brute-forceable by an attacker who has both the commitment
and the salt — encryption doesn't have this weakness for the same field
sizes.

### Limitations of the chosen redaction scheme

- **Fields must be declared sensitive at write time.** A field not listed in
  `sensitiveFields` when the record was created can never be redacted later
  — there is no retroactive "encrypt this existing plaintext field" path.
  This is a deliberate scope boundary ("structured" redaction, not
  free-form), not an oversight.
- **Redaction grain is a whole top-level field**, not a nested path. A
  top-level field whose value is itself a nested object is encrypted as one
  opaque blob; there's no dot-notation support for redacting one nested
  sub-field independently.
- **Key material is stored in plaintext bytes in `redaction_keys`** (no
  envelope encryption / KMS-backed root key). Acceptable for this exercise;
  a production deployment would wrap each field key with a root key managed
  by a proper KMS rather than storing raw AES keys in a table next to the
  data they protect.
- **Redaction is a SQL `DELETE`, not guaranteed secure erasure.** Postgres
  WAL, replication streams, and backups taken before the delete may retain
  the key material for a time. True "right to be forgotten" guarantees
  require coordinating retention/erasure of those secondary copies too —
  out of scope here.
- **Concurrent redaction of the same field** is safe by construction (a
  second `DELETE` for an already-deleted key is a no-op), but two
  simultaneous *first* redaction requests can both observe "key present"
  before either deletes it, producing two `FIELD_REDACTED` audit events
  instead of one. Harmless (redaction still succeeds, idempotency only
  guarantees "at least once" under a race, not "exactly once"), but worth
  noting as a known edge case rather than a guarantee.
- **The redaction action itself is recorded as a normal, chain-protected
  `FIELD_REDACTED` audit event** (mirrors `AuditEventFailureLogger`'s
  self-logging pattern) — this gives tamper-evidence over *when* and *who*
  redacted a field, but that event's payload only carries `fieldPath`/
  `reason`, not the original value (by design — it can't, without defeating
  the redaction).

### Retention — soft-delete flag, not physical archival

**Chosen: an `archived_at` timestamp column on `audit_events`, set once from
`NULL`.** Archived rows never leave the table, and `archived_at` is not part
of any hashed field, so archiving a record changes nothing that
`AuditEventChainVerificationService` checks — the verifier walks archived
rows exactly like any other row, with no special-case gap-handling logic.
This directly avoids the trap the PRD's phrasing implies: a naive
"physically move/delete the row" implementation would either break sequence
contiguity or require the verifier to become aware of an external archive
store to stitch the chain back together. The chosen design sidesteps that
complexity entirely at the cost of not actually reducing primary storage
(see limitations below). `GET /audit/verify` additionally reports
`archivedRecordsCount` so a caller can see archived rows were included, not
silently skipped.

The Postgres immutability trigger (`V3`) is narrowed in `V6` to allow
**exactly one** legitimate transition: `archived_at` going from `NULL` to
non-null, with every other column required to be byte-identical
(`IS NOT DISTINCT FROM`, so `NULL`s compare correctly) between `OLD` and
`NEW`. Un-archiving, archiving combined with any other column change, and
`DELETE` (even on an already-archived row) all remain unconditionally
rejected — see `AuditEventRetentionIT` for the tests exercising each of
these boundary cases directly via SQL.

Archival itself (`AuditEventRepository.archiveEligible`) is a native bulk
`UPDATE ... SET archived_at = :x WHERE archived_at IS NULL AND
server_timestamp < :cutoff`, deliberately **not** an entity `save()` after
mutating a loaded `AuditEvent` in Java — a `save()` risks Hibernate
re-marshalling `Instant` columns (`serverTimestamp`, `createdAt`) at a
different sub-microsecond precision than what's stored, which would
spuriously fail the trigger's byte-identical comparison on every other
column. The bulk `UPDATE` never touches those columns' values at the SQL
level, so this risk doesn't apply.

### Limitations of the chosen retention scheme

- **No actual storage reduction.** Archived records stay in the primary
  table and are still loaded on every full-table `/audit/verify` call — this
  compounds the pre-existing O(n) verification cost noted in Scenario A. A
  "move to cold storage" design would address storage cost but at the price
  of a materially more complex, archive-aware verifier; that trade was
  judged not worth it for this exercise.
- **Trigger function hardcodes every `audit_events` column by name** for the
  `IS NOT DISTINCT FROM` comparison. Any future column addition must be
  added to `V6`'s function body (via a new migration) or the trigger will
  silently stop protecting it — a real, easy-to-forget maintenance coupling.
- **Retention is triggered on demand** (`POST /audit/retention/archive`),
  not on a schedule. A production deployment would likely wrap this in a
  `@Scheduled` job; not added here to keep the archival action explicit and
  directly testable.

## Bulk export — verifiability and its limits

Each exported record carries its own `contentHash`/`recordHash`/
`previousHash`/`sequenceId`, plus **two** payload representations:
`storedPayload` (verbatim, ciphertext-bearing where applicable — what the
hash actually covers) and `payload` (decrypted-or-`"[REDACTED]"` display
form). Conflating the two would make every record with a sensitive field
falsely report `CONTENT_HASH_MISMATCH` on verification — `AuditExportService`
recomputes hashes from `storedPayload` only, never `payload`.

On top of per-record hashes, the bundle carries a **manifest hash**
(`SHA-256` over the ordered concatenation of included `recordHash` values)
and an **HMAC-SHA256 signature** over that manifest, using a server-held
secret (`audit.security.export-signing-key`). `POST /audit/export/verify`
recomputes all three layers independently. This gives a recipient three
distinct guarantees: (1) no single record's content was altered after
export (per-record hash recompute), (2) no record was added, removed, or
reordered after export (manifest hash), and (3) the bundle was genuinely
produced by this service, not fabricated wholesale (HMAC signature — without
it, anyone could construct an entirely new, internally self-consistent fake
bundle, since SHA-256 is public and unkeyed).

**Limitations:**

- **No full genesis-to-here provenance.** The bundle proves nothing in it
  was altered *since export*; it does not prove the first included record's
  `previousHash` is legitimate all the way back to the genesis record —
  that would require either trusting the exporter or cross-checking against
  a live `/audit/verify` call on the source system.
- **Filter semantics are mutually exclusive**, matching the PRD's literal
  "for a given resourceId or actorId": exactly one of `actorId` or
  `resourceType` (+ optional `resourceId`) may be supplied, unlike the more
  permissive AND-based combination allowed by `GET /audit/events`. This is a
  deliberate divergence from the query endpoint, not an oversight.
- **No streaming/cursor-based export.** The whole matching result set is
  loaded into memory; a hard cap (`audit.export.max-records`, default
  10,000) returns `400` above that rather than risking unbounded memory use.
  Acceptable for this exercise's data volumes, not for arbitrarily large
  histories.
- **Empty exports are well-defined, not an error.** Zero matching records
  produce `recordCount: 0` and `manifestHash = SHA-256("")` (a fixed,
  known value), which verifies as `valid: true`.

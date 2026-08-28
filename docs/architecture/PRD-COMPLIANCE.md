# Scenario A — PRD Compliance & Gap Analysis

Compares the PRD (`docs/prd/Interviews.AIProficient.Assignment.AuditLog.pdf`,
§5, Scenario A) against the code as of commit `2295111`. See also
[`C4-diagram.md`](./C4-diagram.md) for the structural view.

## Requirement-by-requirement

| PRD requirement | Status | Notes |
|---|---|---|
| Write API accepts eventType, actorId, resourceType, resourceId, payload, timestamp | ✅ Done | `CreateAuditEventRequest` + `AuditEventController.createAuditEvent`. |
| `timestamp` choice documented (caller vs server) | ⚠️ Partial | Both exist: server-assigned `serverTimestamp` (authoritative, part of the hash, used for query filtering) and optional caller-supplied `clientTimestamp` (excluded from the hash, stored as metadata only). Reasonable design, but the choice and rationale live only in `docs/PROMPT_HISTORY.md`, not in a discoverable architecture doc. |
| Append-only — no update/delete exposed | ✅ Done, and reinforced | `PUT`/`PATCH`/`DELETE` on `/audit/events/{id}` return `405`. A Postgres `BEFORE UPDATE OR DELETE` trigger (`V3__add_audit_events_immutability_trigger.sql`) also unconditionally rejects mutation at the DB layer — defense in depth beyond what the PRD asked for. |
| Query API: filter by actorId, resourceType+resourceId, eventType, time range | ✅ Done | `AuditEventQueryService` builds a composable `Specification`. One added constraint not in the PRD: `resourceId` requires `resourceType` to also be supplied (400 otherwise) — a reasonable assumption (a bare `resourceId` filter is ambiguous across resource types) but worth calling out as a design choice. |
| Pagination for large result sets | ✅ Done | Page/size params, default `page=0,size=20`, capped at `size=100` (undocumented-in-PRD but sensible default; should be stated as an assumption). |
| Each record has a content hash + previous-record hash (or genesis) | ✅ Done | `AuditEventHasher`: `contentHash = SHA256(canonical JSON of eventType/actorId/resourceType/resourceId/payload/serverTimestamp)`; `recordHash = SHA256(contentHash + previousHash)`; genesis = 64 `'0'` chars. |
| Chain Verification Endpoint at `GET /audit/verify` | ⚠️ Path deviation | Implemented at **`GET /audit/events/verify`** (nested under the events resource), not the literal `GET /audit/verify` the PRD specifies. Functionally arguably cleaner (keeps all audit-event operations under one resource), but it is a literal deviation from the written spec and should be called out explicitly if not intentionally re-scoped. |
| Verify reports whether chain is intact | ✅ Done | `intact: boolean`. |
| Verify reports first inconsistency + violation type | ✅ Done, exceeds spec | Reports `sequenceId`, `violationType` (`SEQUENCE_GAP` / `CONTENT_HASH_MISMATCH` / `RECORD_HASH_MISMATCH` / `PREVIOUS_HASH_MISMATCH`), plus `expectedValue`/`actualValue`/`details`, and stops at the *first* break (doesn't try to report every downstream break, which would all be consequences of the first). |
| Validation flow: write → query → verify → tamper directly in the data store → verify again | ⚠️ Works, but undocumented friction | The DB trigger that enforces immutability also blocks the PRD's own validation step, since it rejects **any** `UPDATE`/`DELETE`, including a reviewer's manual tamper attempt via `psql`. The test suite works around this with `ALTER TABLE audit_events DISABLE TRIGGER audit_events_immutable_trigger;` before tampering and `ENABLE TRIGGER` after. **This is not documented anywhere for a human running the manual validation flow** (README doesn't mention it) — a reviewer following the PRD's literal instructions ("modify a record directly in the data store") would hit a Postgres exception and might reasonably conclude the tamper step is blocked rather than realize they need to disable the trigger first. |

## Assumptions made (not stated explicitly in the PRD)

1. **`payload` is transported as a JSON-encoded string, not a nested JSON object.** `CreateAuditEventRequest.payload` is typed `String` with `@NotBlank`, so callers must send `"payload": "{\"ip\":\"1.2.3.4\"}"` rather than `"payload": {"ip":"1.2.3.4"}`. The PRD describes payload as "a structured object." This works (and simplifies exact-byte hashing) but is less ergonomic than a native nested object and is a deviation worth defending explicitly at review.
2. **No validation that `payload` is well-formed JSON before it reaches the database.** `AuditEventHasher.parseJsonIfString` silently falls back to hashing the raw string if parsing fails, but the JSONB column will still reject non-JSON text at insert time. A malformed payload therefore surfaces as an unhandled `500` (Postgres `DataIntegrityViolationException`) rather than a clean `400`, and there is no test covering this path.
3. **`resourceId` without `resourceType` is rejected** (400) rather than treated as a global filter — an assumption about query semantics, not stated in the PRD.
4. **Max page size of 100** — a reasonable but undocumented-in-PRD operational limit.
5. **`eventType` is a free-form string, not a closed enum** — matches the PRD's "e.g." phrasing (examples, not an exhaustive list), so treated as open-ended.
6. **No authentication/authorization on any endpoint**, including `/audit/events/verify` and the write endpoint. Reasonable for a scoped prototype exercise where "no external application or consumer is required," but should be named explicitly as an out-of-scope trade-off rather than left implicit, since an audit log with an unauthenticated write API is a real production concern.

## Trade-offs

- **Two-layer immutability (API 405 + DB trigger)** costs a small amount of extra migration/test complexity but means the append-only guarantee holds even against someone with direct DB access using a non-application credential — which is the actual threat model an audit log needs to defend against (the API layer alone only stops well-behaved API clients). The cost is the validation-flow friction noted above.
- **Full-chain verification is O(n) per call**, loading every row via `findAll(Sort...)` into memory (`AuditEventChainVerificationService.verifyChain`). The `lastN` parameter is an unrequested but sensible mitigation for large logs, but even that still fetches the *entire* table first (`findAll` before sub-listing) — so it doesn't actually save query cost today, only client-facing verification cost. For a real production system this would need either a persisted "last verified checkpoint" or a streaming/paged verification strategy.
- **Canonical hashing via a fresh, separately-configured `ObjectMapper` inside `AuditEventHasher.toCanonicalJson`** (rather than the injected, app-wide `ObjectMapper` bean) guarantees the hash format is stable even if the app's main Jackson config changes later — a deliberate defensive choice, at the cost of a second Jackson configuration to keep in sync by hand.
- **Storing failure/violation events by reusing `AuditEventHasher`/`AuditEventRepository`** (`AuditEventFailureLogger`) means audit-of-the-audit-log is "free" (same chain, same guarantees) but also means a flood of validation failures (e.g., a misbehaving client retrying invalid requests) grows the very log being protected — no separate error-log sink or rate limiting exists.

## Documentation / deliverable gaps

- **`README.md` is stale**: it still describes the repo as being at "project skeleton stage" with the domain model and endpoints "not implemented yet," which was true at commit `462974f` but not since `2914028`. Anyone reading the README today gets an inaccurate picture of what's built, including the false claim that the timestamp caller-vs-server decision is "not yet decided."
- **No standalone architecture overview** exists yet as a deliverable (§7 requires one covering "components, data model, API design, key decisions, and trade-offs including hash algorithm choice and chain design"). The reasoning currently lives only in scattered `PROMPT_HISTORY.md` entries and code comments. This gap analysis + the C4 diagrams narrow it, but a data-model/API-design writeup is still missing.
- **Testing-approach/limitations doc** (§7 deliverable) doesn't exist yet as a standalone artifact, though the test suite itself is solid (38 tests spanning hashing, persistence, write API, query filters, immutability enforcement, and all four verification violation types).

## What's confirmed *not* built yet (by design — out of scope for this pass)

No retention/archival, redaction, or bulk-export code exists (Scenario B), and no compliance-reporting requirement-clarification or implementation exists (Scenario C). This is expected — the PRD explicitly sequences A → B → C as separate scenarios — but is noted here so the compliance picture for *this* codebase snapshot is unambiguous.

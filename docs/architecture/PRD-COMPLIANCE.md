# Scenario A — PRD Compliance & Gap Analysis

Compares the PRD (`docs/prd/Interviews.AIProficient.Assignment.AuditLog.pdf`,
§5, Scenario A) against the code. See also [`C4-diagram.md`](./C4-diagram.md)
for the structural view and
[`ASSUMPTIONS-AND-TRADEOFFS.md`](./ASSUMPTIONS-AND-TRADEOFFS.md) for the full
assumptions/trade-offs writeup (this doc only summarizes them per-requirement
below).

## Requirement-by-requirement

| PRD requirement | Status | Notes |
|---|---|---|
| Write API accepts eventType, actorId, resourceType, resourceId, payload, timestamp | ✅ Done | `CreateAuditEventRequest` + `AuditEventController.createAuditEvent`. |
| `timestamp` choice documented (caller vs server) | ⚠️ Partial | Both exist: server-assigned `serverTimestamp` (authoritative, part of the hash, used for query filtering) and optional caller-supplied `clientTimestamp` (excluded from the hash, stored as metadata only). Reasonable design, but the choice and rationale live only in `docs/PROMPT_HISTORY.md`, not in a discoverable architecture doc. |
| Append-only — no update/delete exposed | ✅ Done, and reinforced | `PUT`/`PATCH`/`DELETE` on `/audit/events/{id}` return `405`. A Postgres `BEFORE UPDATE OR DELETE` trigger (`V3__add_audit_events_immutability_trigger.sql`) also unconditionally rejects mutation at the DB layer — defense in depth beyond what the PRD asked for. |
| Query API: filter by actorId, resourceType+resourceId, eventType, time range | ✅ Done | `AuditEventQueryService` builds a composable `Specification`. One added constraint not in the PRD: `resourceId` requires `resourceType` to also be supplied (400 otherwise) — a reasonable assumption (a bare `resourceId` filter is ambiguous across resource types) but worth calling out as a design choice. |
| Pagination for large result sets | ✅ Done | Page/size params, default `page=0,size=20`, capped at `size=100` (undocumented-in-PRD but sensible default; should be stated as an assumption). |
| Each record has a content hash + previous-record hash (or genesis) | ✅ Done | `AuditEventHasher`: `contentHash = SHA256(canonical JSON of eventType/actorId/resourceType/resourceId/payload/serverTimestamp)`; `recordHash = SHA256(contentHash + previousHash)`; genesis = 64 `'0'` chars. |
| Chain Verification Endpoint at `GET /audit/verify` | ✅ Done | Implemented at `GET /audit/verify` (moved to a dedicated `AuditChainController` under `/audit`, separate from the `/audit/events` resource, to match the PRD's literal path). |
| Verify reports whether chain is intact | ✅ Done | `intact: boolean`. |
| Verify reports first inconsistency + violation type | ✅ Done, exceeds spec | Reports `sequenceId`, `violationType` (`SEQUENCE_GAP` / `CONTENT_HASH_MISMATCH` / `RECORD_HASH_MISMATCH` / `PREVIOUS_HASH_MISMATCH`), plus `expectedValue`/`actualValue`/`details`, and stops at the *first* break (doesn't try to report every downstream break, which would all be consequences of the first). |
| Validation flow: write → query → verify → tamper directly in the data store → verify again | ⚠️ Works, but undocumented friction | The DB trigger that enforces immutability also blocks the PRD's own validation step, since it rejects **any** `UPDATE`/`DELETE`, including a reviewer's manual tamper attempt via `psql`. The test suite works around this with `ALTER TABLE audit_events DISABLE TRIGGER audit_events_immutable_trigger;` before tampering and `ENABLE TRIGGER` after. **This is not documented anywhere for a human running the manual validation flow** (README doesn't mention it) — a reviewer following the PRD's literal instructions ("modify a record directly in the data store") would hit a Postgres exception and might reasonably conclude the tamper step is blocked rather than realize they need to disable the trigger first. |

## Assumptions & trade-offs

Moved to their own doc — see
[`ASSUMPTIONS-AND-TRADEOFFS.md`](./ASSUMPTIONS-AND-TRADEOFFS.md) for the full
list (timestamp handling, payload transport shape, query semantics,
page-size cap, the two-layer immutability design, verification cost, and the
API-key auth gate added to close the "no authentication" gap noted in an
earlier pass of this document).

## Documentation / deliverable gaps

- ~~**`README.md` is stale**~~ — fixed: rewritten to reflect the current
  implementation, API surface, auth requirement, and a manual validation
  walkthrough mirroring the PRD's write→query→verify→tamper→verify flow.
- **No standalone architecture overview** existed as a deliverable (§7
  requires one covering "components, data model, API design, key decisions,
  and trade-offs including hash algorithm choice and chain design"). This gap
  analysis, the C4 diagrams, and the assumptions/trade-offs doc together now
  cover it; a dedicated data-model/API-design writeup beyond what's in the
  README's API surface table is still the thinnest part.
- **Testing-approach/limitations doc** (§7 deliverable) doesn't exist yet as a
  standalone artifact, though the test suite itself is solid (42 tests
  spanning hashing, persistence, write API, query filters, immutability
  enforcement, all four verification violation types, and the API-key
  filter).

## What's confirmed *not* built yet (by design — out of scope for this pass)

No retention/archival, redaction, or bulk-export code exists (Scenario B), and no compliance-reporting requirement-clarification or implementation exists (Scenario C). This is expected — the PRD explicitly sequences A → B → C as separate scenarios — but is noted here so the compliance picture for *this* codebase snapshot is unambiguous.

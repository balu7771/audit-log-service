# Scenario C — Compliance Reporting: Technical Design

**Status:** Design only — not implemented. This document is the deliverable
for this pass; implementation is explicitly deferred (see
[Scope boundary](#scope-boundary)).

## 1. The requirement, as given

> Product says: *"Regulators need to be able to audit access to client
> account data."*

Per the PRD (`docs/prd/`, §5, Scenario C), this is **intentionally
under-specified**. The rest of this document works through clarifying it
before proposing any design.

## 2. Why this isn't already solved by Scenario A/B

The service already exposes `GET /audit/events` (filtered query) and
`GET /audit/export` (self-contained verifiable bundle). A regulator can
already retrieve raw records. So the real question isn't "can a regulator
get data out of this system" — it's **what shape of question is a regulator
actually asking, and does today's API answer it without manual assembly**.

A regulator's typical question is aggregate, not record-level: *"who
accessed account 12345 in Q1, and how many times did each person view vs.
modify it?"* Answering that today means paging through
`GET /audit/events?resourceType=ACCOUNT&resourceId=12345&from=...&to=...`
and manually tallying actors and event types client-side. That manual
assembly step is the gap this design closes.

## 3. Ambiguities identified, and how each was resolved

| # | Ambiguity | Resolution | Why |
|---|---|---|---|
| 1 | Does "access" mean *reads* only, *writes* only, or both? | **Both, but must be distinguishable in reports.** A regulator cares whether an actor *viewed* an account vs. *changed* it — those are different compliance questions (privacy exposure vs. data integrity). | Collapsing them into one count would make the report unable to answer either question precisely. |
| 2 | What's the actual deliverable — better docs on existing endpoints, a new aggregate report, or a scheduled/retained report artifact? | **A new, dedicated aggregated compliance-report endpoint**, built on top of existing query infrastructure. | Existing endpoints already satisfy raw record retrieval; a scheduled/retained artifact is a *retention-policy* decision (see [Open Questions](#5-open-questions), Q3) orthogonal to what data the report contains — that can be layered on later without changing the report's shape. |
| 3 | Should regulators use the same shared API key as every other caller (writers, redactors)? | **Design a distinct, read-only regulator role — but do not implement it in this pass.** | Giving a regulator the same key that can also write events, redact fields, or archive records is a real compliance gap (a regulator credential should not be able to alter the trail it's auditing), but building full multi-key/RBAC is a scope increase beyond what this pass can defend. Documented as a designed-but-deferred piece, consistent with the existing single-shared-key trade-off already accepted in [Scenario A](../architecture/ASSUMPTIONS-AND-TRADEOFFS.md#scenario-a). |
| 4 | Is "client account data" a specific, known `resourceType` (e.g. `ACCOUNT`), or could it be any resource a caller happens to tag as account-related? | **Left as an open question, not assumed.** | `eventType`/`resourceType` are deliberately free-form strings by existing design (no closed enum — see [Scenario A assumption #5](../architecture/ASSUMPTIONS-AND-TRADEOFFS.md#scenario-a)). Nothing today guarantees every producer that writes account-related events uses a consistent `resourceType` value, or that "client account data" maps cleanly onto `resourceType` at all (it could span multiple resource types, or live at the payload-field level). Assuming a convention here would be building on sand; this needs a real answer from whoever owns the account-data model before the report's filtering logic can be trusted. |

## 4. Clarified requirement statement

> **Regulators must be able to retrieve an aggregated report of all access —
> both read (view) and write (create/update) — to a given client account's
> data over a specified time range, showing which actors accessed it, how
> many times, of what type, and when, without needing to manually assemble
> or tally raw event records. The report must be backed by the service's
> existing tamper-evidence guarantees, and regulator credentials must not be
> capable of writing, redacting, or archiving records.**

This is the statement the design below is built against. It intentionally
does **not** claim to guarantee that *all* access to account data anywhere
in the business is captured — see [Risk 1](#6-risks--trade-offs) — only that
whatever access *was* reported to this service can be audited accurately
and safely.

## 5. Open questions

These are not resolved by assumption; they'd block implementation and need
an answer from a product/compliance stakeholder first.

1. **What `resourceType`(s) constitute "client account data"?** Is there a
   canonical list (e.g. `ACCOUNT` only, or `ACCOUNT` + `PORTFOLIO` +
   `STATEMENT`), and who owns that list going forward?
2. **Does the audit requirement need push/scheduled reports** (e.g. a
   monthly filing sent to a regulator automatically), or is on-demand query
   sufficient? This changes whether a report is ever persisted/retained in
   its own right.
3. **What retention period applies to compliance-relevant records**, and is
   it different from the cost-driven archival window already built in
   [Scenario B](../architecture/ASSUMPTIONS-AND-TRADEOFFS.md#retention--soft-delete-flag-not-physical-archival)?
   Regulatory retention (e.g. multi-year, tamper-proof) is a different
   requirement from "archive old records to save space" and may need its
   own window/guarantees.
4. **Do regulators need actor attribution beyond `actorId`** (IP address,
   session, device)? The `payload` field can carry this today if a producer
   chooses to include it, but nothing requires it.
5. **Is under-reporting an accepted risk, or does "audit access" imply an
   enforcement mechanism** upstream (e.g. a shared client library that
   producers must use, so a read handler can't skip emitting the audit
   event)? This service is a passive store — it cannot detect access it was
   never told about.

## 6. Risks / trade-offs

1. **This service can only report on access it's told about.** It has no
   visibility into whether an upstream system actually calls
   `POST /audit/events` for every read of account data — a business system
   with a bug, or one that simply never integrated, produces a silent gap a
   regulator can't distinguish from "no access occurred." This is the
   single biggest limitation of the whole compliance-reporting premise and
   is called out explicitly rather than implied away. See Open Question 5.
2. **Backward compatibility of the new `accessType` field** (see
   [§7](#7-design)). Records written under Scenario A/B have no such field;
   a report spanning historical data must treat pre-existing records as
   `UNKNOWN` rather than silently misclassifying them as reads or writes.
3. **Aggregation performance.** A naive implementation that reuses the
   full-chain-walk pattern from `AuditEventChainVerificationService` (see
   [Scenario A trade-off #2](../architecture/ASSUMPTIONS-AND-TRADEOFFS.md#scenario-a))
   would not scale — a report is a `GROUP BY actorId, accessType` aggregation
   over a filtered, indexed range, not a full-table walk, and should be
   built as a SQL aggregate query via `AuditEventRepository`, not by loading
   every matching row into the JVM.
4. **A report that names which actors accessed which accounts is itself
   sensitive.** It must inherit the same access-control seriousness as the
   raw data it summarizes — which is precisely why the regulator role
   ([§7.3](#73-regulator-access-designed-not-implemented)) matters, even
   undeployed: this document treats "who can see the report" as a first-
   class design constraint, not an afterthought.
5. **Adding a required field changes the write contract.** Every existing
   producer integration would need to start sending `accessType` on every
   `POST /audit/events` call; a rollout needs a transition window (e.g. the
   field defaults to `UNKNOWN` for some period, with a deprecation date
   after which it's enforced as required) rather than a flag-day cutover.

## 7. Design

### 7.1 Data model: `accessType` classification

Add a required field to the write path, `accessType`, an open string (not a
closed enum, matching the existing precedent of `eventType` being
free-form — see [Scenario A assumption #5](../architecture/ASSUMPTIONS-AND-TRADEOFFS.md#scenario-a))
with two conventional values producers are expected to use: `READ` and
`WRITE`. It is:

- Part of `CreateAuditEventRequest`, alongside the existing `eventType`.
- Included in the hashed content (`AuditEventHasher`'s canonical JSON gains
  one more fixed-order field), so it inherits the same tamper-evidence
  guarantee as every other event field — a regulator can trust that a
  report's `READ`/`WRITE` breakdown wasn't altered after the fact, not just
  that the count of events is correct.
- **Nullable on existing rows** (pre-migration data), reported as `UNKNOWN`
  in aggregations rather than backfilled — backfilling would mean guessing,
  which is worse than an honest "we don't know" for a compliance artifact.

This is an additive schema/hash change, same category of change as adding
`sensitive_fields`/`archived_at` in Scenario B — it does not require
re-hashing existing records, only affects records written after the
migration lands.

### 7.2 New endpoint: aggregated compliance report

```
GET /audit/compliance/account-access-report
    ?resourceType=ACCOUNT&resourceId=12345      (mutually exclusive with actorId, matching /audit/export's convention)
    |actorId=actor-1
    &from=2026-01-01T00:00:00Z&to=2026-03-31T23:59:59Z
```

Response shape:

```json
{
  "scope": { "resourceType": "ACCOUNT", "resourceId": "12345", "from": "...", "to": "..." },
  "totalEvents": 47,
  "readAccessCount": 40,
  "writeAccessCount": 6,
  "unknownAccessTypeCount": 1,
  "distinctActorCount": 5,
  "byActor": [
    { "actorId": "actor-1", "readCount": 12, "writeCount": 2, "firstAccess": "...", "lastAccess": "..." }
  ],
  "firstAccess": "2026-01-03T09:12:00Z",
  "lastAccess": "2026-03-30T17:44:00Z",
  "integrity": { "chainIntact": true, "recordsVerified": 47 }
}
```

- Built on `AuditEventRepository`'s existing `Specification` composition
  (same filters as `AuditEventQueryService`), plus a `GROUP BY actorId,
  accessType` aggregate query — not a full-table walk.
- The `integrity` block reuses
  `AuditEventChainVerificationService`, scoped to *only the records in this
  report's result set* (not the whole table), giving the regulator an
  attestation that the specific records summarized haven't been tampered
  with, without re-introducing the O(n) full-chain cost noted in
  [Scenario A](../architecture/ASSUMPTIONS-AND-TRADEOFFS.md#scenario-a) for
  every report request.
- Deliberately returns **aggregates and metadata only, never raw
  `payload` contents** — if a regulator needs the underlying records, they
  drill down via the existing `GET /audit/events` or `GET /audit/export`
  with the same filters. Keeping the report metadata-only limits its
  blast radius if a report itself leaks.

### 7.3 Regulator access (designed, not implemented)

Extend `ApiKeyAuthFilter` from a single shared secret to a small keyed map:
`api-key -> role`, where role is `WRITER` (today's behavior: full access)
or `REGULATOR` (read-only: `GET /audit/events`, `GET /audit/verify`,
`GET /audit/export`, `POST /audit/export/verify`,
`GET /audit/compliance/account-access-report`). A `REGULATOR`-scoped key
gets `403` on `POST /audit/events`, `POST /audit/events/{id}/redactions`,
and `POST /audit/retention/archive`.

This is a natural, additive extension of the existing filter (no change to
its core "gate everything behind `X-API-Key`" model) — but is left
unimplemented here because it's a genuine scope increase (config shape,
role-checking middleware, tests for every endpoint's role matrix) relative
to what this design pass can responsibly claim to have validated. Today,
in the interim, a regulator would use the same shared key as any other
caller — documented as an accepted, temporary gap, not a silent one.

## 8. Scope boundary

**In scope for this design pass:**
- Clarified requirement statement and identified ambiguities (§§2–5)
- `accessType` field design and its hash/backward-compatibility implications (§7.1)
- Aggregated compliance-report endpoint: request/response shape, aggregation strategy, integrity attestation (§7.2)
- Regulator read-only role: designed at the config/filter level (§7.3)

**Explicitly out of scope for this pass (no code changes):**
- Any implementation of the above — this document only.
- Resolving the `resourceType` taxonomy question (Open Question 1) — needs a stakeholder answer first.
- Scheduled/pushed/retained report generation (Open Question 2).
- A distinct regulatory retention window separate from Scenario B's archival window (Open Question 3).
- Full multi-key/RBAC infrastructure beyond the two-role `WRITER`/`REGULATOR` split sketched in §7.3.
- Any upstream enforcement mechanism guaranteeing producers actually emit access events (Risk 1 / Open Question 5) — accepted as a fundamental limitation of a passive downstream audit store, not something this service can close on its own.

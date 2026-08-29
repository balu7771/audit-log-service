# Quick Reference - Audit Log Service API Collection

## Collection Map

```
bruno_collections/
├── bruno.json                              # Collection metadata
├── README.md                               # Full documentation
├── QUICK_REFERENCE.md                      # This file
├── TEST_EXECUTION_GUIDE.md                 # Step-by-step test instructions
├── environments/
│   └── local.json                          # Environment variables for local dev
│
├── Scenario A - Core Audit Log Service/    # 9 requests - Core audit logging
│   ├── 01. Create Audit Event - USER_LOGIN.bru
│   ├── 02. Create Audit Event - RECORD_UPDATED.bru
│   ├── 03. Create Audit Event - PERMISSION_GRANTED.bru
│   ├── 04. Query Events - All Events.bru
│   ├── 05. Query Events - Filter by Actor.bru
│   ├── 06. Query Events - Filter by Resource.bru
│   ├── 07. Query Events - Filter by Event Type.bru
│   ├── 08. Verify Chain Integrity - Full Chain.bru
│   └── 09. Verify Chain Integrity - Last N Records.bru
│
└── Scenario B - Retention and Redaction/   # 9 requests - Advanced features
    ├── 01. Create Events for Export.bru
    ├── 02. Create Second Event for Export.bru
    ├── 03. Create Third Event for Export.bru
    ├── 04. Archive Eligible Records.bru
    ├── 05. Redact Sensitive Field.bru
    ├── 06. Export Bundle by Resource.bru
    ├── 07. Export Bundle by Actor.bru
    ├── 08. Verify Export Bundle.bru
    └── 09. Query Events - Include Archived.bru
```

---

## API Endpoints Summary

| Scenario | Method | Endpoint | Purpose |
|----------|--------|----------|---------|
| **A** | POST | `/audit/events` | Create audit event |
| **A** | GET | `/audit/events` | Query with filters |
| **A** | GET | `/audit/verify` | Verify chain integrity |
| **B** | POST | `/audit/retention/archive` | Soft-archive old records |
| **B** | POST | `/audit/events/{id}/redactions` | Redact sensitive field |
| **B** | GET | `/audit/export` | Export verifiable bundle |
| **B** | POST | `/audit/export/verify` | Verify bundle cryptographically |

---

## Key Concepts at a Glance

### Hash Chain (Scenario A)
```
Event 1: contentHash=C1 → recordHash=R1, previousHash=genesis
Event 2: contentHash=C2 → recordHash=R2, previousHash=R1  ← linked!
Event 3: contentHash=C3 → recordHash=R3, previousHash=R2  ← linked!

Modification detected: Change Event 2 actor → C2 changes → R2 changes 
→ Event 3's previousHash no longer matches R2 → CHAIN BROKEN ✅ Tampering detected
```

### Redaction (Scenario B)
```
At Creation:
  payload: "customerId": "CUST-5678"
  sensitiveFields: ["customerId"]
  contentHash: hash(payload)

On Redaction:
  Destroy encryption key for customerId
  contentHash still valid (still covers original value)
  field unrecoverable but hash proof intact ✅

Result: Satisfies both tamper-proof and privacy requirements
```

### Export Bundle (Scenario B)
```
Bundle = [Records] + [Manifest] + [Signature]

Records: Full events with hashes
Manifest: SHA-256 hash of all records
Signature: HMAC-SHA-256(manifest)

Verification: Recompute hashes/HMAC, verify all match
→ Independent verification without service ✅
→ Tamper detection: Any change invalidates signature
```

---

## Request Checklist

### Scenario A - Quick Run
- [ ] A.1: Create USER_LOGIN → Note sequenceId
- [ ] A.2: Create RECORD_UPDATED → Verify previousHash = A.1's recordHash
- [ ] A.3: Create PERMISSION_GRANTED → Verify chain continues
- [ ] A.4: Query All → Should get 3 events
- [ ] A.5: Query by Actor (admin@example.com) → Should get 1 event
- [ ] A.6: Query by Resource (ACCOUNT/acc-789) → Should get 1 event
- [ ] A.7: Query by Type (RECORD_UPDATED) → Should get 1 event
- [ ] A.8: Full Verify → `intact: true`, no violations
- [ ] A.9: Last N Verify → `intact: true` for last 2 records

### Scenario B - Quick Run
- [ ] B.1: Create DATA_ACCESS → Note sequenceId (for redaction)
- [ ] B.2: Create ACCOUNT_MODIFICATION → Same resource (cust-1001)
- [ ] B.3: Create DATA_DOWNLOAD → Completes 3-event set
- [ ] B.4: Archive → Records marked with archivedAt
- [ ] B.5: Redact (use sequenceId from B.1) → Field destroyed
- [ ] B.6: Export by Resource → Get bundle with manifest + signature
- [ ] B.7: Export by Actor → Alternative export criteria
- [ ] B.8: Verify Bundle → `valid: true`, all integrity checks pass
- [ ] B.9: Query Archived → Can still retrieve archived records

---

## Common Parameter Values

| Parameter | Value | Example |
|-----------|-------|---------|
| `actorId` | User/system ID | `admin@example.com`, `user@example.com` |
| `resourceType` | Resource category | `USER_SESSION`, `ACCOUNT`, `CUSTOMER_RECORD` |
| `resourceId` | Specific resource | `session-12345`, `acc-789`, `cust-1001` |
| `eventType` | Event category | `USER_LOGIN`, `RECORD_UPDATED`, `PERMISSION_GRANTED` |
| `page` | Page number (0-indexed) | `0`, `1`, `2` |
| `size` | Records per page | `20`, `50`, `100` |
| `windowDays` | Retention window | `0`, `7`, `365` |
| `lastN` | Last N records to verify | `10`, `100`, `500` |

---

## Expected Status Codes

| Code | Meaning | Common Scenario |
|------|---------|-----------------|
| 200 | OK | Query, Verify, Archive, Export |
| 201 | Created | Event creation successful |
| 400 | Bad Request | Invalid parameters, field not sensitive |
| 404 | Not Found | Event sequenceId doesn't exist |
| 500 | Server Error | Database error, service issue |

---

## Response Validation Quick Guide

### Create Event Response
```json
{
  "sequenceId": 1,           // ✅ Unique, incremented
  "eventType": "...",         // ✅ Matches request
  "actorId": "...",           // ✅ Matches request
  "contentHash": "...",       // ✅ 40+ char hash
  "recordHash": "...",        // ✅ 40+ char hash
  "previousHash": "...",      // ✅ genesis or previous recordHash
  "serverTimestamp": "...",   // ✅ ISO 8601 format
  "sensitiveFields": [...]    // ✅ Declared fields
}
```

### Query Response
```json
{
  "content": [...events...],  // ✅ Array of events
  "pageable": {
    "totalElements": 3,       // ✅ Total matching records
    "totalPages": 1,          // ✅ Pages needed
    "pageNumber": 0,          // ✅ Current page
    "first": true,            // ✅ First page?
    "last": true              // ✅ Last page?
  }
}
```

### Verify Chain Response
```json
{
  "intact": true,             // ✅ Chain unbroken?
  "totalRecords": 3,          // ✅ Records verified
  "verifiedRecordsCount": 3,  // ✅ Actually verified
  "violation": null           // ✅ No tampering detected
}
```

### Export Bundle Response
```json
{
  "exportedAt": "...",        // ✅ Export timestamp
  "recordCount": 3,           // ✅ Records in bundle
  "records": [...],           // ✅ Full event details
  "manifest": {               // ✅ Cryptographic summary
    "algorithm": "SHA-256",
    "manifestHash": "..."
  },
  "signature": {              // ✅ HMAC authentication
    "algorithm": "HMAC-SHA-256",
    "value": "..."
  }
}
```

### Verify Bundle Response
```json
{
  "valid": true,              // ✅ Bundle unmodified?
  "perRecordIntact": true,    // ✅ All record hashes valid?
  "manifestIntact": true,     // ✅ Manifest hash valid?
  "signatureIntact": true,    // ✅ HMAC signature valid?
  "firstViolation": null      // ✅ No tampering?
}
```

---

## Workflow Sequences

### Single Record Lifecycle
```
1. Create Event (POST /audit/events)
   ↓ (Optional) 2. Redact Field (POST /audit/events/{id}/redactions)
   ↓ (Optional) 3. Archive (POST /audit/retention/archive)
   ↓ 4. Query (GET /audit/events)
   ↓ 5. Verify (GET /audit/verify)
   ↓ 6. Export (GET /audit/export)
   ↓ 7. Verify Bundle (POST /audit/export/verify)
```

### Scenario A Workflow
```
Create Events (3) → Query (4 ways) → Verify Chain (2 ways)
                              ↓
                    Tamper-proof proof ✅
```

### Scenario B Workflow
```
Create Events (3) → Archive → Redact → Export → Verify Bundle
                    ↓         ↓        ↓         ↓
            Data Retention  Privacy  Shareability  Proof
```

---

## Tips & Tricks

### Import into Postman
1. Use environment variables: `{{baseUrl}}`, `{{sequenceId}}`
2. Scripts can auto-set variables from responses
3. Pre-request scripts can validate test prerequisites
4. Tests tab can assert expected values

### Bruno Specific
1. Keyboard shortcut: Cmd/Ctrl+K to search requests
2. Environments: Set in collection or request level
3. Variables: `{{variable}}` syntax in URL, body, headers
4. Run collection: Run all requests sequentially

### Debug Tips
- **Copy response as cURL**: See exact HTTP request/response
- **Check timestamps**: Should increment for sequential events
- **Validate hashes**: Length should be consistent (40-64 chars)
- **Inspect payload**: JSON payload must be valid string

### Performance
- **Pagination**: Use `size=50` for faster loads, not `size=5000`
- **Filter early**: Use `actorId`, `resourceId` to reduce result set
- **Bulk operations**: Archive with larger `windowDays` is faster
- **Export large exports**: May timeout, use resource/actor filters

---

## Troubleshooting Matrix

| Problem | Cause | Solution |
|---------|-------|----------|
| 404 Not Found | Service not running | Start service on port 8080 |
| Connection refused | Wrong host/port | Verify http://localhost:8080 |
| 400 Invalid params | Wrong parameter type | page/size are integers |
| previousHash mismatch | Events out of order | Create events sequentially |
| Redaction fails: not declared | Field not in sensitiveFields | Re-create event with field marked |
| Bundle verify fails | Data modified after export | Use exact bundle response |
| No archived records | windowDays too large | Use windowDays=0 for immediate |

---

## Import/Export Formats

### Bruno Format (.bru)
- Native Bruno request format
- Includes metadata, auth, params, body, docs
- Can be version controlled in Git
- Import: `File → Open Collection`

### Postman Format
- JSON format (can export from Bruno)
- Import: `File → Import → Upload JSON`
- Environments: Separate `.json` file
- Postman Cloud sync available

### cURL Format
- From "Copy as cURL" option
- Use in scripts, CI/CD pipelines
- Easy to debug HTTP traffic

---

## Collection Statistics

| Metric | Value |
|--------|-------|
| Total Requests | 18 |
| Scenario A | 9 requests |
| Scenario B | 9 requests |
| API Endpoints | 7 unique |
| Documentation | 3 guides |
| Estimated Runtime | 15-20 min |

---

## Quick Links

- **Full Docs**: README.md
- **Step-by-Step**: TEST_EXECUTION_GUIDE.md
- **Swagger UI**: http://localhost:8080/swagger-ui
- **API Spec**: http://localhost:8080/v3/api-docs
- **GitHub Repo**: [Your repo link]

---

## Support Resources

### Common Questions
- **Q**: Can I modify a record? **A**: No, append-only. No PUT/PATCH/DELETE.
- **Q**: Can I unarchive? **A**: Not in current API, soft delete only.
- **Q**: Can I reverse redaction? **A**: No, crypto-shredding is irreversible.
- **Q**: How long should verification take? **A**: Seconds for <10K records.
- **Q**: Max bundle size? **A**: Depends on service config, use filters.

### Debugging Checklist
- [ ] Service running and healthy
- [ ] Check `/swagger-ui` for actual endpoints
- [ ] Validate JSON in request body
- [ ] Use exact parameter types (string, integer, boolean)
- [ ] Try one request in isolation first
- [ ] Check service logs for errors
- [ ] Verify environment variables set correctly

---

**Last Updated**: 2026-08-29  
**Version**: 1.0

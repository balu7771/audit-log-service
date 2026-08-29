# Test Execution Guide - Audit Log Service Collection

## Quick Start

### Prerequisites
- Bruno installed: https://www.getbruno.com
- Service running on `http://localhost:8080`
- Service started and healthy (check: `http://localhost:8080/swagger-ui/index.html`)

### Setup
1. Clone/open Bruno
2. Open this collection: `File → Open Collection → bruno_collections/`
3. (Optional) Set environment to "Local Development"
4. Start executing requests

---

## Scenario A: Core Audit Log Service (Full Workflow)

**Objective**: Verify core audit logging with tamper-proof hash chain

**Estimated Time**: 5-10 minutes

### Phase 1: Create Events (Establish the Chain)

#### Test A.1: USER_LOGIN Event
- **Request**: `Scenario A - Core Audit Log Service/01. Create Audit Event - USER_LOGIN.bru`
- **Method**: POST /audit/events
- **Expected Status**: 201 Created

**Validation Checklist**:
- ✅ Response status is 201
- ✅ `sequenceId` is present (note this value)
- ✅ `contentHash` is present (40-64 char hash)
- ✅ `recordHash` is present
- ✅ `previousHash` equals "genesis" or similar value (first record)
- ✅ `serverTimestamp` is set to current time
- ✅ `actorId` = "user@example.com"
- ✅ `eventType` = "USER_LOGIN"

**Response Example**:
```json
{
  "sequenceId": 1,
  "eventType": "USER_LOGIN",
  "actorId": "user@example.com",
  "resourceType": "USER_SESSION",
  "resourceId": "session-12345",
  "payload": "{\"ipAddress\": \"192.168.1.100\", \"userAgent\": \"Mozilla/5.0\", \"loginMethod\": \"password\"}",
  "serverTimestamp": "2026-08-29T14:30:00.123Z",
  "clientTimestamp": null,
  "contentHash": "7a42f5c3d8b1e9a4f6c2d5e8b1a4f7c2d5e8b1a4",
  "recordHash": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "previousHash": "genesis",
  "archivedAt": null,
  "sensitiveFields": []
}
```

---

#### Test A.2: RECORD_UPDATED Event
- **Request**: `Scenario A/02. Create Audit Event - RECORD_UPDATED.bru`
- **Method**: POST /audit/events
- **Expected Status**: 201 Created

**Validation Checklist**:
- ✅ Response status is 201
- ✅ `sequenceId` is 2 (incremented from previous)
- ✅ `previousHash` **equals the recordHash from Test A.1** (CHAIN LINKAGE)
- ✅ `contentHash` is different from Test A.1
- ✅ `recordHash` is different from Test A.1
- ✅ `sensitiveFields` includes "accountNumber"
- ✅ `actorId` = "admin@example.com"

**Key Validation**: The `previousHash` in this response should exactly match the `recordHash` from the previous response. This is the hash chain linkage.

**Troubleshooting**:
- If previousHash doesn't match: Check if events created in correct order
- If sequenceId not incremented: Database sequence issue

---

#### Test A.3: PERMISSION_GRANTED Event
- **Request**: `Scenario A/03. Create Audit Event - PERMISSION_GRANTED.bru`
- **Method**: POST /audit/events
- **Expected Status**: 201 Created

**Validation Checklist**:
- ✅ Response status is 201
- ✅ `sequenceId` is 3
- ✅ `previousHash` **equals recordHash from Test A.2** (continues chain)
- ✅ `eventType` = "PERMISSION_GRANTED"
- ✅ Chain now has: A.1 → A.2 → A.3

**Expected Chain State**:
```
Event 1 (A.1): contentHash=C1, recordHash=R1, previousHash=genesis
                                    ↓ (linked by R1)
Event 2 (A.2): contentHash=C2, recordHash=R2, previousHash=R1
                                    ↓ (linked by R2)
Event 3 (A.3): contentHash=C3, recordHash=R3, previousHash=R2
```

---

### Phase 2: Query Events (Verify Filtering)

#### Test A.4: Query All Events
- **Request**: `Scenario A/04. Query Events - All Events.bru`
- **Method**: GET /audit/events?page=0&size=20
- **Expected Status**: 200 OK

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `content` array has 3 events
- ✅ `pageable.totalElements` = 3
- ✅ `pageable.pageNumber` = 0
- ✅ Events in content array match the three created

**Sample Response Structure**:
```json
{
  "content": [
    { sequenceId: 1, eventType: "USER_LOGIN", ... },
    { sequenceId: 2, eventType: "RECORD_UPDATED", ... },
    { sequenceId: 3, eventType: "PERMISSION_GRANTED", ... }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "totalElements": 3,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

---

#### Test A.5: Filter by Actor
- **Request**: `Scenario A/05. Query Events - Filter by Actor.bru`
- **Method**: GET /audit/events?actorId=admin@example.com&page=0&size=20
- **Expected Status**: 200 OK

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `content` array has 1 event (the RECORD_UPDATED)
- ✅ Event in content has `actorId` = "admin@example.com"
- ✅ Events by other actors (user@example.com, security-admin@example.com) excluded

---

#### Test A.6: Filter by Resource
- **Request**: `Scenario A/06. Query Events - Filter by Resource.bru`
- **Method**: GET /audit/events?resourceType=ACCOUNT&resourceId=acc-789&page=0&size=20
- **Expected Status**: 200 OK

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `content` array has 1 event (RECORD_UPDATED)
- ✅ Event has `resourceType` = "ACCOUNT" and `resourceId` = "acc-789"
- ✅ Other resources filtered out

---

#### Test A.7: Filter by Event Type
- **Request**: `Scenario A/07. Query Events - Filter by Event Type.bru`
- **Method**: GET /audit/events?eventType=RECORD_UPDATED&page=0&size=20
- **Expected Status**: 200 OK

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `content` array has 1 event
- ✅ Event has `eventType` = "RECORD_UPDATED"
- ✅ Other event types filtered out

---

### Phase 3: Verify Chain Integrity (Tamper Detection)

#### Test A.8: Full Chain Verification
- **Request**: `Scenario A/08. Verify Chain Integrity - Full Chain.bru`
- **Method**: GET /audit/verify
- **Expected Status**: 200 OK

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `intact` = true (chain not broken)
- ✅ `totalRecords` = 3
- ✅ `lastVerifiedSequenceId` = 3
- ✅ `verifiedRecordsCount` = 3
- ✅ `archivedRecordsCount` = 0
- ✅ `violation` = null (no tampering detected)

**Sample Response**:
```json
{
  "intact": true,
  "totalRecords": 3,
  "lastVerifiedSequenceId": 3,
  "verifiedRecordsCount": 3,
  "archivedRecordsCount": 0,
  "violation": null
}
```

**What This Means**:
- All hashes valid
- Chain unbroken
- No modifications detected
- Tamper-proof guarantee holds

---

#### Test A.9: Last N Records Verification
- **Request**: `Scenario A/09. Verify Chain Integrity - Last N Records.bru`
- **Method**: GET /audit/verify?lastN=2
- **Expected Status**: 200 OK

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `intact` = true
- ✅ `verifiedRecordsCount` = 2 (only last 2, not all 3)
- ✅ `lastVerifiedSequenceId` = 3
- ✅ `violation` = null

---

## Scenario B: Retention and Redaction (Full Workflow)

**Objective**: Test advanced features: retention policies, sensitive field redaction, bulk export

**Estimated Time**: 10-15 minutes

**Prerequisites**: Scenario A completed OR fresh service (events can be in any state)

### Phase 1: Create Multi-Event Resource (Setup for Export)

#### Test B.1: DATA_ACCESS Event
- **Request**: `Scenario B/01. Create Events for Export.bru`
- **Method**: POST /audit/events
- **Expected Status**: 201 Created

**Validation Checklist**:
- ✅ Response status is 201
- ✅ `sequenceId` noted (will use later for redaction)
- ✅ `actorId` = "analyst@example.com"
- ✅ `resourceId` = "cust-1001"
- ✅ `sensitiveFields` includes "customerId"

**⚠️ Important**: Save the `sequenceId` from this response. You'll need it for Test B.5 (Redact).

**Example sequenceId**: Let's say response shows `"sequenceId": 10`

---

#### Test B.2: ACCOUNT_MODIFICATION Event
- **Request**: `Scenario B/02. Create Second Event for Export.bru`
- **Method**: POST /audit/events
- **Expected Status**: 201 Created

**Validation Checklist**:
- ✅ Response status is 201
- ✅ `sequenceId` is incremented
- ✅ `resourceId` = "cust-1001" (same resource as B.1)
- ✅ `sensitiveFields` includes "phoneNumber" and "ssn"
- ✅ `previousHash` equals recordHash from B.1 (chain continues)

---

#### Test B.3: DATA_DOWNLOAD Event
- **Request**: `Scenario B/03. Create Third Event for Export.bru`
- **Method**: POST /audit/events
- **Expected Status**: 201 Created

**Validation Checklist**:
- ✅ Response status is 201
- ✅ `resourceId` = "cust-1001" (same resource)
- ✅ Now have 3 events for cust-1001
- ✅ Chain intact: B.1 → B.2 → B.3

---

### Phase 2: Retention Policy (Archive Old Records)

#### Test B.4: Archive Eligible Records
- **Request**: `Scenario B/04. Archive Eligible Records.bru`
- **Method**: POST /audit/retention/archive?windowDays=0
- **Expected Status**: 200 OK

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `archivedCount` > 0 (records from A and B are archived)
- ✅ `cutoffTimestamp` is set
- ✅ `windowDaysUsed` = 0

**Sample Response**:
```json
{
  "archivedCount": 6,
  "cutoffTimestamp": "2026-08-29T14:30:00Z",
  "windowDaysUsed": 0
}
```

**What Archival Does**:
- Marks records older than window with `archivedAt` timestamp
- Doesn't delete (soft delete)
- Hash chain untouched
- Still queryable with `includeArchived=true`

---

### Phase 3: Sensitive Field Redaction (Crypto-Shredding)

#### Test B.5: Redact Sensitive Field
- **Request**: `Scenario B/05. Redact Sensitive Field.bru`
- **Method**: POST /audit/events/{{sequenceId}}/redactions
- **Expected Status**: 200 OK

**⚠️ IMPORTANT - Must Update Request**:
In the URL, replace `{{sequenceId}}` with the actual sequenceId from Test B.1.

For example, if B.1 returned `"sequenceId": 10`, change URL to:
```
POST /audit/events/10/redactions
```

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `sequenceId` matches the event you redacted
- ✅ `fieldPath` = "customerId"
- ✅ `alreadyRedacted` = false (first redaction)
- ✅ `redactionAuditEventSequenceId` is present (audit event created)

**Sample Response**:
```json
{
  "sequenceId": 10,
  "fieldPath": "customerId",
  "alreadyRedacted": false,
  "redactionAuditEventSequenceId": 13
}
```

**What Redaction Does**:
- Destroys encryption key for "customerId" field
- Field becomes unrecoverable
- Original contentHash still valid
- Creates audit event (sequenceId 13 in example) recording who redacted what and why
- Event still queryable but field value inaccessible

---

### Phase 4: Bulk Export (Self-Verifiable Bundle)

#### Test B.6: Export by Resource
- **Request**: `Scenario B/06. Export Bundle by Resource.bru`
- **Method**: GET /audit/export?resourceType=CUSTOMER_RECORD&resourceId=cust-1001
- **Expected Status**: 200 OK

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `resourceType` = "CUSTOMER_RECORD"
- ✅ `resourceId` = "cust-1001"
- ✅ `recordCount` = 3 (the 3 events for this resource)
- ✅ `records` array has 3 objects
- ✅ `manifest` present with algorithm and manifestHash
- ✅ `signature` present with algorithm and value

**Understanding the Bundle**:

**records**: Full event details with hashes
```json
{
  "sequenceId": 10,
  "eventType": "DATA_ACCESS",
  "actorId": "analyst@example.com",
  "resourceType": "CUSTOMER_RECORD",
  "resourceId": "cust-1001",
  "payload": "{...}",
  "storedPayload": null,
  "serverTimestamp": "2026-08-29T14:25:00Z",
  "contentHash": "abc123...",
  "recordHash": "def456...",
  "previousHash": "genesis or previous hash...",
  "archivedAt": "2026-08-29T14:30:00Z"
}
```

**manifest**: Cryptographic summary of all records
```json
{
  "algorithm": "SHA-256",
  "manifestHash": "mani789abc..."
}
```

**signature**: HMAC authentication
```json
{
  "algorithm": "HMAC-SHA-256",
  "value": "sig012def..."
}
```

---

#### Test B.7: Export by Actor
- **Request**: `Scenario B/07. Export Bundle by Actor.bru`
- **Method**: GET /audit/export?actorId=analyst@example.com
- **Expected Status**: 200 OK

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `actorId` = "analyst@example.com"
- ✅ `resourceType` = null (not filtered by resource)
- ✅ `recordCount` ≥ 1 (at least the DATA_ACCESS event)
- ✅ All records in bundle have `actorId` = "analyst@example.com"
- ✅ `manifest` and `signature` present

---

### Phase 5: Bundle Verification (Independent Verification)

#### Test B.8: Verify Export Bundle
- **Request**: `Scenario B/08. Verify Export Bundle.bru`
- **Method**: POST /audit/export/verify
- **Expected Status**: 200 OK

**⚠️ IMPORTANT - Manual Body Update**:
This request needs the actual bundle from B.6 as body:
1. Run Test B.6
2. Copy entire response JSON
3. In Test B.8 request body, replace the example bundle with actual bundle
4. Keep the structure the same (just update values)

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `valid` = true (bundle unmodified)
- ✅ `recordCount` matches bundle's recordCount
- ✅ `perRecordIntact` = true (all record hashes valid)
- ✅ `manifestIntact` = true (manifest hash matches)
- ✅ `signatureIntact` = true (HMAC signature valid)
- ✅ `firstViolation` = null (no tampering)

**Sample Response**:
```json
{
  "valid": true,
  "recordCount": 3,
  "perRecordIntact": true,
  "manifestIntact": true,
  "signatureIntact": true,
  "firstViolation": null
}
```

**What This Means**:
- Bundle has not been modified
- Every record hash is valid
- Manifest is valid
- Signature is valid
- Can trust bundle as evidence

---

#### Test B.9: Query Archived Events
- **Request**: `Scenario B/09. Query Events - Include Archived.bru`
- **Method**: GET /audit/events?resourceType=CUSTOMER_RECORD&resourceId=cust-1001&includeArchived=true
- **Expected Status**: 200 OK

**Validation Checklist**:
- ✅ Response status is 200
- ✅ `content` array has 3 events
- ✅ Events have `archivedAt` timestamp populated
- ✅ Can still query archived records

---

## Complete Test Summary

### Scenario A Flow
```
Create 3 Events
      ↓
Query (All, by Actor, by Resource, by Type)
      ↓
Verify Chain (Full, Last N)
      ↓
Result: Integrity guaranteed ✅
```

### Scenario B Flow
```
Create 3 Events (same resource)
      ↓
Archive Eligible Records
      ↓
Redact Sensitive Field
      ↓
Export Bundle
      ↓
Verify Bundle Independently
      ↓
Query Including Archived
      ↓
Result: Retention + Redaction + Export verified ✅
```

---

## Tamper Testing (Optional but Recommended)

### Manual Tamper Test
1. Run Scenario A (Tests A.1-A.9) to establish clean chain
2. Note the `recordHash` from Test A.2 response
3. Manually modify the database:
   - Find record with sequenceId=2
   - Change actor name from "admin@example.com" to "hacker@example.com"
   - Save database
4. Run Test A.8 (Verify Chain) again
5. **Expected Result**: `intact: false` with violation details

**Expected Violation**:
```json
{
  "intact": false,
  "violation": {
    "sequenceId": 2,
    "violationType": "CONTENT_HASH_MISMATCH",
    "expectedValue": "original contentHash...",
    "actualValue": "different hash after modification",
    "details": "Record 2 contentHash does not match stored hash"
  }
}
```

---

## Performance Notes

- **Pagination**: Default page=0, size=20. Large result sets use pagination.
- **Last N Verification**: Use `?lastN=100` for quick verification of recent records
- **Bulk Export**: Large exports may take time; service may have size limits
- **Archive**: Archiving millions of records may take time; use async patterns in production

---

## Troubleshooting

### Connection Issues
```
Error: Cannot reach http://localhost:8080
Solution: Start the service, verify it's healthy at /swagger-ui
```

### 400 Bad Request
```
Error: Invalid query parameters
Solution: Check parameter types (page/size are integers, dates are ISO format)
```

### 404 Not Found
```
Error: Endpoint not found
Solution: Check Swagger UI for actual endpoint, verify service API version
```

### Verification Shows Tampering
```
Scenario: You haven't manually modified database, but verification fails
Solution: 
- Ensure events created in correct sequence
- Don't mix Scenario A and B events (both may modify same chain)
- Fresh service recommended for each scenario
```

### Redaction Sequencing
```
Error: Field not found / Field not declared sensitive
Solution: 
- Use sequenceId from event creation response
- Ensure sensitiveFields array included the field name at creation
- Redact using exact fieldPath from payload JSON
```

---

## Tips & Best Practices

1. **Test in Order**: Run requests sequentially as shown in guide
2. **Note Response Values**: Save sequenceIds, hashes for later use
3. **Environment Variables**: Use environments to avoid hard-coding IDs
4. **Monitor Timestamps**: serverTimestamp should increase for each event
5. **Verify Often**: Run chain verification after each creation phase
6. **Export Before Redact**: Export bundle before redacting (cleaner data)
7. **Document Results**: Save verification results as proof of integrity

---

## Success Criteria

### Scenario A Complete When:
- ✅ 3 events created with valid hashes
- ✅ All 4 query filters work correctly
- ✅ Full chain verification: intact=true
- ✅ Last N verification: intact=true

### Scenario B Complete When:
- ✅ 3 events created on same resource
- ✅ Archive: archivedCount > 0
- ✅ Redaction: successful response
- ✅ Export: bundle with records+manifest+signature
- ✅ Verify: valid=true
- ✅ Query archived: shows archived records

---

**Last Updated**: 2026-08-29  
**Collection Version**: 1.0

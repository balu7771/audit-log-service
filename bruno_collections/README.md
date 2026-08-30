# Audit Log Service - Bruno API Test Collection

## Overview

This Bruno collection provides comprehensive API testing coverage for the **Audit Log Service** - a tamper-evident audit logging system with hash-chain integrity, retention policies, and redaction capabilities.

The collection is organized into two primary scenarios:
- **Scenario A**: Core Audit Log Service (basic write, query, and verification)
- **Scenario B**: Retention and Redaction (archival, soft deletion, sensitive field redaction, bulk export)

## Collection Structure

```
bruno_collections/
├── README.md (this file)
├── bruno.json (collection metadata)
├── Scenario A - Core Audit Log Service/
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
└── Scenario B - Retention and Redaction/
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

## Getting Started

### Prerequisites
- Bruno API Client installed ([getbruno.com](https://www.getbruno.com))
- Audit Log Service running on `http://localhost:8080`
- Service endpoints available at `/swagger-ui/index.html`

### Importing the Collection

#### Option 1: Bruno Native
1. Open Bruno
2. Click "Open Collection" (or Cmd+O / Ctrl+O)
3. Navigate to this directory: `bruno_collections/`
4. Select the folder and import

#### Option 2: Postman Compatibility
This collection is structured to be **Postman-compatible**:
1. Export as Postman collection (if Bruno supports export)
2. Or manually import into Postman by copying the request definitions

## Scenario A: Core Audit Log Service

### Overview
Tests the fundamental audit log operations:
- **Write API**: Create immutable audit events
- **Query API**: Retrieve events with filtering
- **Tamper Detection**: Hash chain verification

### Test Sequence

#### Step 1: Create Events (Tests 1-3)
Create three different types of audit events to build a hash chain:

1. **USER_LOGIN** - User authentication event
2. **RECORD_UPDATED** - Account data modification
3. **PERMISSION_GRANTED** - Access control change

Each event includes:
- Event metadata (type, actor, resource, timestamp)
- Structured payload with event-specific details
- Sensitive field declarations
- Server-generated hash chain (contentHash, recordHash, previousHash)

**Expected Outcome**: Three linked records forming an immutable chain.

#### Step 2: Query Events (Tests 4-7)
Verify filtering capabilities work correctly:

4. **Query All Events** - Retrieve complete audit trail
5. **Filter by Actor** - Find all events by specific user
6. **Filter by Resource** - Track all changes to one resource
7. **Filter by Event Type** - Find all events of specific type

**Expected Outcome**: Filtered results correctly show subset of events.

#### Step 3: Verify Chain Integrity (Tests 8-9)
Prove tamper-proof guarantee:

8. **Full Chain Verification** - Verify all records in chain
9. **Last N Verification** - Verify only recent records

**Expected Outcome**: `"intact": true` with no violations detected.

### Key Concepts

#### Hash Chain
Each record contains:
- **contentHash**: Hash of event fields (payload, actor, resource, etc.)
- **recordHash**: Hash of (contentHash + previousHash) - creates chain linkage
- **previousHash**: Points to previous record's recordHash

**Tamper Guarantee**: Modifying any field invalidates its contentHash and breaks all subsequent recordHashes.

#### Verification
The `/audit/verify` endpoint:
1. Starts with genesis value for first record
2. Recomputes all hashes
3. Confirms chain integrity
4. Returns first violation point if tampering detected

## Scenario B: Retention and Redaction

### Overview
Tests advanced features for data governance:
- **Retention Policies**: Archival of old records
- **Sensitive Field Redaction**: Crypto-shredding without breaking hash chain
- **Bulk Export**: Self-contained, independently verifiable bundles
- **Archive Handling**: Verify chain with archived records

### Test Sequence

#### Step 1: Create Events (Tests 1-3)
Create events on a single resource (Customer Record) with sensitive fields:

1. **DATA_ACCESS** - Access event with customerId (marked sensitive)
2. **ACCOUNT_MODIFICATION** - Account details with PII (phoneNumber, ssn)
3. **DATA_DOWNLOAD** - Export/download event

**Expected Outcome**: Events linked in chain, sensitive fields marked for redaction.

#### Step 2: Retention Policy (Test 4)
Archive old records:

4. **Archive Eligible Records** - Soft-delete records older than window

**How Archival Works**:
- Records marked with `archivedAt` timestamp
- Not physically deleted (still exist for compliance)
- Hash chain NOT modified or touched
- Chain verification ignores archived gaps correctly

**Expected Outcome**: Records marked archived, chain still verifiable.

#### Step 3: Redaction (Test 5)
Redact sensitive field:

5. **Redact Sensitive Field** - Destroy decryption key for customerId

**Crypto-Shredding Process**:
- Field declared sensitive at creation time
- Redaction endpoint called with field path
- Encryption key destroyed irreversibly
- Field becomes unrecoverable
- Original contentHash still valid (covers original content)
- Redaction action itself creates audit event

**Expected Outcome**: Field marked as redacted, hash chain unchanged.

#### Step 4: Bulk Export (Tests 6-7)
Export verifiable bundles:

6. **Export by Resource** - All events for cust-1001
7. **Export by Actor** - All events by analyst@example.com

**Bundle Contents**:
- All matching records with full details
- Hash chain information (contentHash, recordHash, previousHash)
- Manifest: Hash of all records
- Signature: HMAC signature for authentication

**Properties**:
- Self-contained (no service needed to verify)
- Independently verifiable
- Tamper-proof (signature invalidates if modified)
- Can verify hash chain in isolation

**Expected Outcome**: Bundle with records, manifest, and signature.

#### Step 5: Bundle Verification (Test 8)
Verify exported bundle:

8. **Verify Export Bundle** - Cryptographically verify bundle integrity

**Verification Checks**:
- Per-record: Recompute contentHash and recordHash
- Manifest: Verify manifestHash matches
- Signature: Verify HMAC signature valid

**Expected Outcome**: `"valid": true` with all integrity checks passing.

#### Step 6: Query Archived (Test 9)
Verify archived records queryable:

9. **Query Events - Include Archived** - Retrieve events with archived flag

**Expected Outcome**: Results include both active and archived records.

### Key Concepts

#### Retention and Archival
- **Retention Window**: Configurable days (e.g., 365 days = 1 year retention)
- **Soft Delete**: `archived_at` timestamp marks archival
- **Chain Safe**: Archival never touches hash chain
- **Query Control**: `includeArchived` parameter controls result visibility

#### Redaction (Crypto-Shredding)
**The Challenge**: Original hash covers original value. Removing value invalidates hash.

**The Solution**: Separate encryption and hashing
1. **Store**: Encrypted payload + contentHash (of original plaintext)
2. **On Redaction**: Destroy encryption key, leave hash intact
3. **Result**: Value unrecoverable but hash proof unchanged

**Properties**:
- Hash chain unbroken
- Field still marked as having been sensitive
- Redaction action auditable (creates audit event)
- Satisfies both tampering and privacy requirements

#### Export Bundles
Self-contained verification structure:
- **Records**: Full events with all hash chain data
- **Manifest**: Cryptographic summary of all records
- **Signature**: HMAC signature for authentication

**Workflow**:
1. Service generates bundle (records + manifest + signature)
2. Bundle shared/stored
3. Later: Anyone can verify locally without service
4. Verification: Recompute hashes and signature, confirm match

## API Reference Summary

### Scenario A Endpoints

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/audit/events` | Create audit event |
| GET | `/audit/events` | Query events with filters |
| GET | `/audit/verify` | Verify chain integrity |
| PUT, DELETE, PATCH | `/audit/events/{id}` | Not allowed (return error) |

### Scenario B Endpoints

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/audit/retention/archive` | Archive old records |
| POST | `/audit/events/{id}/redactions` | Redact sensitive field |
| GET | `/audit/export` | Export verifiable bundle |
| POST | `/audit/export/verify` | Verify exported bundle |

## Request/Response Examples

### Create Event
```json
POST /audit/events
{
  "eventType": "USER_LOGIN",
  "actorId": "user@example.com",
  "resourceType": "USER_SESSION",
  "resourceId": "session-12345",
  "payload": "{\"ipAddress\": \"192.168.1.100\", \"userAgent\": \"Mozilla/5.0\"}",
  "sensitiveFields": []
}

Response (201):
{
  "sequenceId": 1,
  "eventType": "USER_LOGIN",
  "actorId": "user@example.com",
  "resourceType": "USER_SESSION",
  "resourceId": "session-12345",
  "payload": "{...}",
  "serverTimestamp": "2026-08-29T14:30:00Z",
  "contentHash": "abc123...",
  "recordHash": "def456...",
  "previousHash": "genesis...",
  "sensitiveFields": []
}
```

### Query Events
```
GET /audit/events?actorId=user@example.com&page=0&size=20

Response (200):
{
  "content": [
    { event objects... }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "totalElements": 50,
    "totalPages": 3,
    "first": true,
    "last": false
  }
}
```

### Verify Chain
```
GET /audit/verify

Response (200):
{
  "intact": true,
  "totalRecords": 3,
  "lastVerifiedSequenceId": 3,
  "verifiedRecordsCount": 3,
  "archivedRecordsCount": 0,
  "violation": null
}
```

### Export Bundle
```
GET /audit/export?resourceType=CUSTOMER_RECORD&resourceId=cust-1001

Response (200):
{
  "exportedAt": "2026-08-29T14:30:00Z",
  "resourceType": "CUSTOMER_RECORD",
  "resourceId": "cust-1001",
  "recordCount": 3,
  "records": [ ... ],
  "manifest": {
    "algorithm": "SHA-256",
    "manifestHash": "mani789..."
  },
  "signature": {
    "algorithm": "HMAC-SHA-256",
    "value": "sig012..."
  }
}
```

## Testing Workflow

### Recommended Test Order
1. **Scenario A - Integrity Tests**
   - Run requests 1-9 sequentially
   - Verify each step produces expected results
   - Confirms append-only, immutable, tamper-proof design

2. **Scenario B - Advanced Features**
   - Run requests 1-9 sequentially
   - Tests retention, redaction, export separately
   - Verifies chain integrity maintained throughout

### Tamper Testing (Manual)
To test tamper detection:
1. Create events (Scenario A, tests 1-3)
2. Run verification (Scenario A, test 8) → should be `intact: true`
3. Manually modify a record in database (e.g., change actor name)
4. Run verification again → should detect tampering:
   ```json
   {
     "intact": false,
     "violation": {
       "sequenceId": 1,
       "violationType": "CONTENT_HASH_MISMATCH",
       "details": "..."
     }
   }
   ```

## Postman Import

While Bruno is the native format, you can use this collection in Postman:

1. **Option A - Export from Bruno**
   - Right-click collection → Export
   - Select Postman format
   - Import into Postman

2. **Option B - Manual Migration**
   - Open each `.bru` file
   - Create request in Postman with same:
     - URL (Method and endpoint)
     - Parameters
     - Body
     - Headers (if any)

3. **Option C - Bruno Integration**
   - Postman can import Bruno collections directly (newer versions)

## Troubleshooting

### Service Not Responding
- Verify service running: `http://localhost:8080/swagger-ui/index.html`
- Check service logs
- Confirm port 8080 not blocked by firewall

### Request Errors
- **400 Bad Request**: Check required fields, parameter types
- **401 Unauthorized**: `X-API-Key` header missing or the key isn't one of `audit.security.api-keys`
- **403 Forbidden**: The key is valid but its role doesn't permit this operation - e.g.
  `writerApiKey` can't query/export, `auditorApiKey` can't create/archive/redact. Use the
  key matching the role noted on that request (see Security Notes below)
- **404 Not Found**: Verify endpoint exists in Swagger UI
- **500 Server Error**: Check service logs

### Chain Verification Fails
- **New service**: Create events first (Scenario A, tests 1-3)
- **Tampering detected**: Inspect violation details for what broke
- **Archived records**: Use `includeArchived=true` if verifying archived events

### Redaction Issues
- Field not declared sensitive: Mark in `sensitiveFields` when creating event
- Sequence ID wrong: Use correct `sequenceId` from create response
- Already redacted: Error if field already redacted (expected behavior)

## Security Notes

- **Never commit sensitive data**: API payloads may contain real data
- **Use environment variables**: Store `baseUrl`, `writerApiKey`, `auditorApiKey`, and
  `adminApiKey` in Bruno environment (see `environments/Local.yml`)
- **RBAC, not one shared key**: each request sets the `X-API-Key` header to the key for
  the role it needs (WRITER to create events, AUDITOR to query/verify/export, ADMIN to
  archive/redact) instead of reusing one all-access token across every request - see
  `docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md` for the role model
- **HTTPS in production**: Local development uses HTTP, use HTTPS for production
- **Rate limiting**: Service may enforce rate limits on bulk operations
- **Access control**: Verify authorization to perform operations

## Performance Considerations

- **Pagination**: Use `page` and `size` for large result sets (default page=0, size=20)
- **lastN parameter**: Use on `/audit/verify?lastN=100` for partial verification
- **Archive old records**: Archival reduces active dataset, improves query performance
- **Bulk exports**: Very large exports may timeout; use resource/actor filters

## Further Reading

See the PRD document for complete specification:
- `docs/prd/Interviews.AIProficient.Assignment.AuditLog.pdf`

Check Swagger UI for full API documentation:
- `http://localhost:8080/swagger-ui/index.html`

## Collection Metadata

- **Name**: Audit Log Service - API Testing Collection
- **Version**: 1.0
- **Format**: Bruno (native) + Postman-compatible
- **Base URL**: http://localhost:8080
- **Scenarios**: A (Core), B (Retention & Redaction)
- **Total Requests**: 18
- **Last Updated**: 2026-08-29

---

**Happy Testing!** 🚀

package com.persistent.auditlog.config;

/**
 * Roles recognized by the {@code X-API-Key} RBAC model. See
 * docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md for why these three and not
 * a finer-grained set.
 */
public enum ApiRole {
    /** Creates audit events (event-producing services / regular users). No read/export access. */
    WRITER,
    /** Read-only compliance access: query, chain verification, export and export verification. */
    AUDITOR,
    /** Data-lifecycle operations: retention archival and field redaction. */
    ADMIN
}

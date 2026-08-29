-- Retention/archival support (soft-delete flag, same table).
--
-- archived_at is not part of any hashed field, so archiving a record never
-- changes content_hash/record_hash/previous_hash - the chain verification
-- algorithm requires no changes to keep passing archived rows.
ALTER TABLE audit_events ADD COLUMN archived_at TIMESTAMP;

CREATE INDEX idx_audit_events_archived_at ON audit_events(archived_at);

-- Partial index to make "find eligible non-archived records older than cutoff"
-- efficient without scanning already-archived rows.
CREATE INDEX idx_audit_events_unarchived_server_timestamp
    ON audit_events(server_timestamp) WHERE archived_at IS NULL;

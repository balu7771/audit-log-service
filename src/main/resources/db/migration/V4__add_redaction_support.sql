-- Structured redaction support (crypto-shredding scheme).
--
-- Sensitive payload fields are encrypted BEFORE the hash chain is computed, so
-- audit_events.content_hash/record_hash always cover ciphertext, never plaintext.
-- Redacting a field later means permanently deleting its key row below - this
-- never touches audit_events, so no immutability-trigger exception is needed
-- for redaction. See docs/architecture/ASSUMPTIONS-AND-TRADEOFFS.md.

-- Declared once at creation time (immutable thereafter - only ever set on INSERT,
-- never UPDATEd, so this does not require any trigger exception).
ALTER TABLE audit_events ADD COLUMN sensitive_fields JSONB;

CREATE TABLE redaction_keys (
    sequence_id BIGINT NOT NULL REFERENCES audit_events(sequence_id),
    field_path VARCHAR(255) NOT NULL,
    encryption_key BYTEA NOT NULL,
    iv BYTEA NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (sequence_id, field_path)
);

-- Deliberately no immutability trigger on this table: hard-deleting a row here
-- (destroying the only copy of a field's decryption key) IS the redaction
-- mechanism. audit_events itself is never mutated by redaction.

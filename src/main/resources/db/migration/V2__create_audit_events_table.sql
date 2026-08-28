-- Create audit_events table with hash chain support
CREATE TABLE audit_events (
    sequence_id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    resource_type VARCHAR(255) NOT NULL,
    resource_id VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    server_timestamp TIMESTAMP NOT NULL,
    client_timestamp TIMESTAMP,
    content_hash VARCHAR(64) NOT NULL,
    record_hash VARCHAR(64) NOT NULL,
    previous_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- Index on sequence_id for fast chain verification
CREATE UNIQUE INDEX idx_audit_events_sequence_id ON audit_events(sequence_id);

-- Indexes for filtering queries
CREATE INDEX idx_audit_events_actor_id ON audit_events(actor_id);
CREATE INDEX idx_audit_events_resource_type_id ON audit_events(resource_type, resource_id);
CREATE INDEX idx_audit_events_event_type ON audit_events(event_type);
CREATE INDEX idx_audit_events_server_timestamp ON audit_events(server_timestamp);

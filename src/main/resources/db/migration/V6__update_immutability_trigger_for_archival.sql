-- Narrow the immutability trigger to allow exactly one legitimate mutation:
-- archiving a not-yet-archived record (setting archived_at from NULL to a
-- timestamp). DELETE remains unconditionally blocked, always, even on
-- already-archived rows. Every column other than archived_at must stay
-- byte-identical (IS NOT DISTINCT FROM, so NULLs compare correctly) or the
-- update is rejected exactly as before.
--
-- Maintenance note: this function hardcodes every audit_events column by name.
-- Any future column addition MUST be added to this comparison list, or the
-- trigger will silently stop protecting it. See ASSUMPTIONS-AND-TRADEOFFS.md.
CREATE OR REPLACE FUNCTION audit_events_immutable_check() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_events table is immutable - DELETE is not allowed';
    END IF;

    IF OLD.archived_at IS NULL
        AND NEW.archived_at IS NOT NULL
        AND NEW.sequence_id IS NOT DISTINCT FROM OLD.sequence_id
        AND NEW.event_type IS NOT DISTINCT FROM OLD.event_type
        AND NEW.actor_id IS NOT DISTINCT FROM OLD.actor_id
        AND NEW.resource_type IS NOT DISTINCT FROM OLD.resource_type
        AND NEW.resource_id IS NOT DISTINCT FROM OLD.resource_id
        AND NEW.payload IS NOT DISTINCT FROM OLD.payload
        AND NEW.server_timestamp IS NOT DISTINCT FROM OLD.server_timestamp
        AND NEW.client_timestamp IS NOT DISTINCT FROM OLD.client_timestamp
        AND NEW.content_hash IS NOT DISTINCT FROM OLD.content_hash
        AND NEW.record_hash IS NOT DISTINCT FROM OLD.record_hash
        AND NEW.previous_hash IS NOT DISTINCT FROM OLD.previous_hash
        AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
        AND NEW.sensitive_fields IS NOT DISTINCT FROM OLD.sensitive_fields
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'audit_events table is immutable - UPDATE and DELETE are not allowed (except archiving a record exactly once)';
END;
$$ LANGUAGE plpgsql;

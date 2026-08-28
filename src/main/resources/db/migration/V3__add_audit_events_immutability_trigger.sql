-- Add trigger to enforce immutability of audit_events
-- Prevents any UPDATE or DELETE operations on audit records

CREATE FUNCTION audit_events_immutable_check() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_events table is immutable - UPDATE and DELETE are not allowed';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_immutable_trigger
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW
EXECUTE FUNCTION audit_events_immutable_check();

-- Baseline migration for audit-log-service.
-- Intentionally minimal: establishes the Flyway migration history and
-- proves the Spring Boot -> Flyway -> PostgreSQL pipeline works end-to-end.
-- Actual schema (e.g. audit_event table) is introduced via subsequent
-- versioned migrations (V2__..., etc.) during the Scenario A TDD phase.
-- Do not add business schema to this file.
COMMENT ON SCHEMA public IS 'audit-log-service managed schema (Flyway)';

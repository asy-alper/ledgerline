-- A separate runtime role is optional for existing PostgreSQL installations.
-- Docker initializes it before migrations. It cannot own tables or disable triggers.
DO $privileges$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledgerline_app') THEN
        GRANT SELECT, INSERT ON users TO ledgerline_app;
        GRANT SELECT, INSERT, DELETE ON sessions TO ledgerline_app;
        -- SELECT FOR UPDATE SKIP LOCKED in session cleanup needs UPDATE on at least one column.
        GRANT UPDATE (expires_at) ON sessions TO ledgerline_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON auth_rate_limits TO ledgerline_app;
        GRANT SELECT, INSERT ON accounts TO ledgerline_app;
        GRANT UPDATE (balance, status) ON accounts TO ledgerline_app;
        GRANT SELECT, INSERT ON transfers, ledger_entries, idempotency_keys, audit_events TO ledgerline_app;
        GRANT SELECT ON flyway_schema_history TO ledgerline_app;
        EXECUTE format('GRANT USAGE ON ALL SEQUENCES IN SCHEMA %I TO ledgerline_app', current_schema());
    END IF;
END;
$privileges$;

#!/bin/sh
set -eu
# Official PostgreSQL image runs this only when its data volume is empty.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=runtime_password="$DATABASE_PASSWORD" <<'SQL'
CREATE ROLE ledgerline_app LOGIN PASSWORD :'runtime_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ledgerline_app;
SQL

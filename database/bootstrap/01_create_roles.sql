-- 01_create_roles.sql
-- Purpose: create the three least-privilege login roles and set their attributes, passwords and settings.
-- Run as: postgres (by run_bootstrap). Idempotent. No DO blocks: psql does not interpolate :'var' inside $$...$$.
-- Expects psql variables owner_pwd, app_pwd, worker_pwd. run_bootstrap fills them with SCRAM-SHA-256 verifiers
-- computed client-side, so no plaintext password ever reaches the server or its log.

SELECT format('CREATE ROLE %I LOGIN', r)
FROM (VALUES ('krz_owner'), ('krz_app'), ('krz_worker')) AS v(r)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r)
\gexec

SET password_encryption = 'scram-sha-256';
SELECT format('ALTER ROLE %I PASSWORD %L', 'krz_owner', :'owner_pwd') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', 'krz_app', :'app_pwd') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', 'krz_worker', :'worker_pwd') \gexec

-- Attributes
ALTER ROLE krz_owner  LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 5;
ALTER ROLE krz_app    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 40;
ALTER ROLE krz_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 10;

-- Role-level settings
ALTER ROLE krz_owner SET search_path = attend;
ALTER ROLE krz_owner SET timezone = 'UTC';

ALTER ROLE krz_app SET search_path = attend;
ALTER ROLE krz_app SET statement_timeout = '30s';
ALTER ROLE krz_app SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE krz_app SET lock_timeout = '10s';
ALTER ROLE krz_app SET timezone = 'UTC';

ALTER ROLE krz_worker SET search_path = attend;
ALTER ROLE krz_worker SET statement_timeout = '5s';
ALTER ROLE krz_worker SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE krz_worker SET lock_timeout = '10s';
ALTER ROLE krz_worker SET timezone = 'UTC';
ALTER ROLE krz_worker SET default_transaction_read_only = on;

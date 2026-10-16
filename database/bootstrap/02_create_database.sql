-- 02_create_database.sql
-- Purpose: create the database krz_attend (owned by krz_owner) and close it to PUBLIC.
-- Run as: postgres (by run_bootstrap, connected to "postgres"). Idempotent; never drops anything.

SELECT 'CREATE DATABASE krz_attend OWNER krz_owner ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'krz_attend')
\gexec

REVOKE ALL ON DATABASE krz_attend FROM PUBLIC;
GRANT CONNECT ON DATABASE krz_attend TO krz_app, krz_worker;

-- 00_check_prerequisites.sql
-- Purpose: refuse PostgreSQL older than 14 and report the server settings the bootstrap depends on.
-- Run as: postgres (by run_bootstrap, connected to the maintenance database "postgres"). Changes nothing.

DO $$
BEGIN
  IF current_setting('server_version_num')::int < 140000 THEN
    RAISE EXCEPTION 'PostgreSQL 14 or newer is required; this server is %. Upgrade it first (database/README.md).',
      current_setting('server_version');
  END IF;
END
$$;

\pset footer off
\echo '== Server'
SELECT name AS setting, setting AS value
FROM (VALUES
        ('server_version',      current_setting('server_version')),
        ('server_version_num',  current_setting('server_version_num')),
        ('password_encryption', current_setting('password_encryption')),
        ('listen_addresses',    current_setting('listen_addresses')),
        ('port',                current_setting('port')),
        ('timezone',            current_setting('TimeZone')),
        ('hba_file',            current_setting('hba_file')),
        ('config_file',         current_setting('config_file')),
        ('server_encoding',     current_setting('server_encoding'))
     ) AS s(name, setting);

\echo '== KRZ Attend objects'
SELECT 'database krz_attend' AS object,
       CASE WHEN EXISTS (SELECT 1 FROM pg_database WHERE datname = 'krz_attend') THEN 'exists' ELSE 'missing' END AS state
UNION ALL
SELECT 'role ' || r.name,
       CASE WHEN EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.name) THEN 'exists' ELSE 'missing' END
FROM (VALUES ('krz_owner'), ('krz_app'), ('krz_worker')) AS r(name);

\echo '== Checks'
SELECT 'password_encryption is scram-sha-256' AS check,
       CASE WHEN current_setting('password_encryption') = 'scram-sha-256' THEN 'ok'
            ELSE 'WARN: the bootstrap sets scram-sha-256 for its own roles; set it server-wide in postgresql.conf' END AS result
UNION ALL
SELECT 'version 15+ recommended',
       CASE WHEN current_setting('server_version_num')::int >= 150000 THEN 'ok' ELSE 'WARN: 14 works; 15+ recommended' END;

\echo '== pg_hba.conf rules (no secrets)'
SELECT line_number, type, database, user_name, address, netmask, auth_method
FROM pg_hba_file_rules
ORDER BY line_number;

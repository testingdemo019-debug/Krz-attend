-- 03_extensions_and_schema.sql
-- Purpose: schema "attend" owned by krz_owner, the extensions INSIDE it, and the default privileges.
-- Run as: postgres, connected to krz_attend (by run_bootstrap). Idempotent.
-- The schema is created first: extensions in "public" would be invisible to roles whose search_path is
-- "attend" (citext would fail in V002, and citext = would silently fall back to case-sensitive text =).
-- gen_random_uuid() (13+) and sha256() (11+) are core, so pgcrypto is not needed.

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA IF NOT EXISTS attend AUTHORIZATION krz_owner;
CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA attend;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA attend;

GRANT USAGE ON SCHEMA attend TO krz_app, krz_worker;

-- Tables and sequences krz_owner creates later (Flyway) are usable by the backend runtime.
ALTER DEFAULT PRIVILEGES FOR ROLE krz_owner IN SCHEMA attend GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO krz_app;
ALTER DEFAULT PRIVILEGES FOR ROLE krz_owner IN SCHEMA attend GRANT USAGE, SELECT ON SEQUENCES TO krz_app;

-- PostgreSQL grants EXECUTE to PUBLIC by default; that would let krz_worker call the SECURITY DEFINER purge
-- function. Functions the app needs are granted one by one in the migrations.
ALTER DEFAULT PRIVILEGES FOR ROLE krz_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- The worker gets no default privileges: V012 grants SELECT on each v_worker_* view individually.

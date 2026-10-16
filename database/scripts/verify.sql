-- verify.sql — sanity checks of roles, grants, triggers, constraints and the audit hash chain.
-- Run AS krz_app (some checks, such as citext resolution, depend on krz_app's own settings):
--   psql -h localhost -p <DB_PORT> -U krz_app -d krz_attend -X -v ON_ERROR_STOP=1 -f database/scripts/verify.sql
-- Exits non-zero if any check fails. Read-only; needs no temporary tables.
\set ON_ERROR_STOP on
\pset footer off

\echo '== Row counts'
SELECT 'employee' AS "table", count(*) AS "rows" FROM attend.employee
UNION ALL SELECT 'permission', count(*) FROM attend.permission
UNION ALL SELECT 'role', count(*) FROM attend.role
UNION ALL SELECT 'user_access', count(*) FROM attend.user_access
UNION ALL SELECT 'location', count(*) FROM attend.location
UNION ALL SELECT 'department', count(*) FROM attend.department
UNION ALL SELECT 'shift', count(*) FROM attend.shift
UNION ALL SELECT 'shift_allocation', count(*) FROM attend.shift_allocation
UNION ALL SELECT 'holiday', count(*) FROM attend.holiday
UNION ALL SELECT 'camera', count(*) FROM attend.camera
UNION ALL SELECT 'face_template', count(*) FROM attend.face_template
UNION ALL SELECT 'crossing', count(*) FROM attend.crossing
UNION ALL SELECT 'review_item', count(*) FROM attend.review_item
UNION ALL SELECT 'attendance_day', count(*) FROM attend.attendance_day
UNION ALL SELECT 'hrms_outbox', count(*) FROM attend.hrms_outbox
UNION ALL SELECT 'notification_rule', count(*) FROM attend.notification_rule
UNION ALL SELECT 'audit_log', count(*) FROM attend.audit_log;

\echo '== Checks'
DO $$
DECLARE
  result   record;
  failures integer := 0;
BEGIN
  FOR result IN
    WITH chain AS (
      SELECT a.code, a.prev_hash, a.row_hash,
             lag(a.row_hash) OVER (ORDER BY substring(a.code FROM 4)::bigint) AS previous_row_hash,
             row_number() OVER (ORDER BY substring(a.code FROM 4)::bigint) AS position,
             sha256(coalesce(a.prev_hash, '\x'::bytea) || convert_to(attend.audit_canonical_text(a), 'UTF8')) AS recomputed
        FROM attend.audit_log AS a
    ),
    checks(n, name, ok) AS (VALUES
      (1, 'connected as krz_app', current_user = 'krz_app'),
      (2, 'roles krz_owner/krz_app/krz_worker exist, LOGIN, not superuser, no CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS',
          (SELECT count(*) = 3 FROM pg_roles
            WHERE rolname IN ('krz_owner', 'krz_app', 'krz_worker')
              AND rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
              AND NOT rolreplication AND NOT rolbypassrls)),
      (3, 'krz_owner is NOINHERIT with connection limit 5',
          (SELECT NOT rolinherit AND rolconnlimit = 5 FROM pg_roles WHERE rolname = 'krz_owner')),
      (4, 'connection limits krz_app 40, krz_worker 10',
          (SELECT count(*) = 2 FROM pg_roles
            WHERE (rolname = 'krz_app' AND rolconnlimit = 40) OR (rolname = 'krz_worker' AND rolconnlimit = 10))),
      (5, 'krz_app settings: search_path=attend, statement_timeout=30s, idle-in-transaction 60s, lock_timeout 10s, UTC',
          (SELECT s.setconfig @> ARRAY['search_path=attend', 'statement_timeout=30s', 'idle_in_transaction_session_timeout=60s',
                                       'lock_timeout=10s', 'TimeZone=UTC']
             FROM pg_db_role_setting AS s JOIN pg_roles AS r ON r.oid = s.setrole
            WHERE r.rolname = 'krz_app' AND s.setdatabase = 0)),
      (6, 'krz_worker settings: statement_timeout=5s and read-only transactions',
          (SELECT s.setconfig @> ARRAY['search_path=attend', 'statement_timeout=5s', 'default_transaction_read_only=on']
             FROM pg_db_role_setting AS s JOIN pg_roles AS r ON r.oid = s.setrole
            WHERE r.rolname = 'krz_worker' AND s.setdatabase = 0)),
      (7, 'PUBLIC has no CONNECT or TEMP on krz_attend',
          NOT has_database_privilege('public', current_database(), 'CONNECT')
          AND NOT has_database_privilege('public', current_database(), 'TEMP')),
      (8, 'PUBLIC cannot CREATE in schema public', NOT has_schema_privilege('public', 'public', 'CREATE')),
      (9, 'schema attend is owned by krz_owner',
          (SELECT pg_get_userbyid(nspowner) = 'krz_owner' FROM pg_namespace WHERE nspname = 'attend')),
      (10, 'extensions citext and btree_gist live in schema attend',
          (SELECT count(*) = 2 FROM pg_extension AS e JOIN pg_namespace AS n ON n.oid = e.extnamespace
            WHERE e.extname IN ('citext', 'btree_gist') AND n.nspname = 'attend')),
      (11, 'citext resolves for krz_app (case-insensitive comparison)', 'A'::citext = 'a'),
      (12, 'krz_app cannot create objects in schema attend (no DDL)', NOT has_schema_privilege('krz_app', 'attend', 'CREATE')),
      (13, 'krz_app can INSERT and SELECT audit_log',
          has_table_privilege('krz_app', 'attend.audit_log', 'INSERT') AND has_table_privilege('krz_app', 'attend.audit_log', 'SELECT')),
      (14, 'krz_app cannot UPDATE, DELETE or TRUNCATE audit_log',
          NOT has_table_privilege('krz_app', 'attend.audit_log', 'UPDATE')
          AND NOT has_table_privilege('krz_app', 'attend.audit_log', 'DELETE')
          AND NOT has_table_privilege('krz_app', 'attend.audit_log', 'TRUNCATE')),
      (15, 'krz_app has no rights on flyway_schema_history (if present)',
          to_regclass('attend.flyway_schema_history') IS NULL
          OR NOT has_table_privilege('krz_app', 'attend.flyway_schema_history', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')),
      (16, 'krz_worker can SELECT all five v_worker_* views',
          (SELECT count(*) = 5 FROM pg_class AS c JOIN pg_namespace AS n ON n.oid = c.relnamespace
            WHERE n.nspname = 'attend' AND c.relkind = 'v' AND c.relname LIKE 'v\_worker\_%'
              AND has_table_privilege('krz_worker', c.oid, 'SELECT'))),
      (17, 'krz_worker has no privilege on any table, sequence or other view',
          (SELECT count(*) = 0 FROM pg_class AS c JOIN pg_namespace AS n ON n.oid = c.relnamespace
            WHERE n.nspname = 'attend' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
              AND NOT (c.relkind = 'v' AND c.relname LIKE 'v\_worker\_%')
              AND has_table_privilege('krz_worker', c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))
          AND (SELECT count(*) = 0 FROM pg_class AS c JOIN pg_namespace AS n ON n.oid = c.relnamespace
            WHERE n.nspname = 'attend'
              -- CASE guards the call: the planner may evaluate predicates in any order.
              AND CASE WHEN c.relkind = 'S' THEN has_sequence_privilege('krz_worker', c.oid, 'USAGE,SELECT,UPDATE') ELSE false END)),
      (18, 'v_worker_* views are security barriers owned by krz_owner',
          (SELECT bool_and(pg_get_userbyid(c.relowner) = 'krz_owner' AND c.reloptions @> ARRAY['security_barrier=true'])
             FROM pg_class AS c JOIN pg_namespace AS n ON n.oid = c.relnamespace
            WHERE n.nspname = 'attend' AND c.relkind = 'v' AND c.relname LIKE 'v\_worker\_%')),
      (19, 'purge_audit: EXECUTE for krz_app only (not PUBLIC, not krz_worker)',
          has_function_privilege('krz_app', 'attend.purge_audit(timestamptz)', 'EXECUTE')
          AND NOT has_function_privilege('krz_worker', 'attend.purge_audit(timestamptz)', 'EXECUTE')
          AND NOT has_function_privilege('public', 'attend.purge_audit(timestamptz)', 'EXECUTE')),
      (20, 'purge_audit is SECURITY DEFINER, owned by krz_owner, with a fixed search_path',
          (SELECT p.prosecdef AND pg_get_userbyid(p.proowner) = 'krz_owner' AND p.proconfig @> ARRAY['search_path=attend, pg_temp']
             FROM pg_proc AS p WHERE p.oid = 'attend.purge_audit(timestamptz)'::regprocedure)),
      (21, 'no function in schema attend is executable by PUBLIC except extension members',
          (SELECT count(*) = 0 FROM pg_proc AS p JOIN pg_namespace AS n ON n.oid = p.pronamespace
            WHERE n.nspname = 'attend' AND has_function_privilege('public', p.oid, 'EXECUTE')
              AND NOT EXISTS (SELECT 1 FROM pg_depend AS d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e'))),
      (22, 'audit_log triggers: chain, no update, no delete, no truncate',
          (SELECT count(*) = 4 FROM pg_trigger
            WHERE tgrelid = 'attend.audit_log'::regclass AND NOT tgisinternal
              AND tgname IN ('trg_audit_log_chain', 'trg_audit_log_no_update', 'trg_audit_log_no_delete', 'trg_audit_log_no_truncate'))),
      (23, 'gallery version triggers on face_template, face_profile, consent and employee',
          (SELECT count(*) = 4 FROM pg_trigger
            WHERE NOT tgisinternal AND tgname IN ('trg_face_template_gallery_version', 'trg_face_profile_gallery_version',
                                                   'trg_consent_gallery_version', 'trg_employee_gallery_version'))),
      (24, 'threshold_profile immutability trigger exists',
          EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'attend.threshold_profile'::regclass AND tgname = 'trg_threshold_profile_immutable')),
      (25, 'every table with updated_at has its set_updated_at trigger',
          (SELECT count(*) = 0 FROM pg_attribute AS a JOIN pg_class AS c ON c.oid = a.attrelid JOIN pg_namespace AS n ON n.oid = c.relnamespace
            WHERE n.nspname = 'attend' AND c.relkind = 'r' AND a.attname = 'updated_at' AND NOT a.attisdropped
              AND NOT EXISTS (SELECT 1 FROM pg_trigger AS t WHERE t.tgrelid = c.oid AND t.tgfoid = 'attend.set_updated_at()'::regprocedure))),
      (26, 'exclusion constraint against overlapping shift allocations exists',
          EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ex_shift_allocation_overlap' AND contype = 'x')),
      (27, 'single-row guarantees: default shift, active threshold profile, active consent notice',
          (SELECT count(*) = 3 FROM pg_indexes WHERE schemaname = 'attend'
            AND indexname IN ('uq_shift_single_default', 'uq_threshold_profile_single_active', 'uq_consent_notice_single_active'))),
      (28, 'every foreign key column is indexed (leading index column)',
          (SELECT count(*) = 0 FROM pg_constraint AS k JOIN pg_namespace AS n ON n.oid = k.connamespace
            WHERE n.nspname = 'attend' AND k.contype = 'f'
              AND NOT EXISTS (SELECT 1 FROM pg_index AS i WHERE i.indrelid = k.conrelid AND i.indkey[0] = k.conkey[1]))),
      (29, '19 permissions; System Administrator holds all of them',
          (SELECT count(*) = 19 FROM attend.permission)
          AND (SELECT count(*) = 19 FROM attend.role_permission AS rp JOIN attend.role AS r ON r.id = rp.role_id WHERE r.code = 'ROLE-SYSADMIN')),
      (30, 'exactly one default shift (with a version), one active threshold profile, one active consent notice',
          (SELECT count(*) = 1 FROM attend.shift AS s WHERE s.is_default AND EXISTS (SELECT 1 FROM attend.shift_version AS v WHERE v.shift_id = s.id))
          AND (SELECT count(*) = 1 FROM attend.threshold_profile WHERE active)
          AND (SELECT count(*) = 1 FROM attend.consent_notice WHERE active)),
      (31, 'notification rules NR-01 … NR-11 present', (SELECT count(*) = 11 FROM attend.notification_rule)),
      (32, 'audit hash chain intact (anchored on the oldest remaining row)',
          (SELECT count(*) = 0 FROM chain
            WHERE row_hash <> recomputed OR (position > 1 AND prev_hash IS DISTINCT FROM previous_row_hash)))
    )
    SELECT * FROM checks ORDER BY n
  LOOP
    IF result.ok IS TRUE THEN
      RAISE NOTICE 'ok    %', result.name;
    ELSE
      RAISE NOTICE 'FAIL  %', result.name;
      failures := failures + 1;
    END IF;
  END LOOP;
  IF failures > 0 THEN
    RAISE EXCEPTION 'verify.sql: % check(s) failed', failures;
  END IF;
  RAISE NOTICE 'verify.sql: all checks passed';
END
$$;

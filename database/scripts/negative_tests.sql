-- negative_tests.sql — proves that the database refuses what it must refuse (database prompt D7).
-- Run it twice, as krz_app and as krz_worker:
--   psql -h localhost -p <DB_PORT> -U krz_app    -d krz_attend -X -v ON_ERROR_STOP=1 -f database/scripts/negative_tests.sql
--   psql -h localhost -p <DB_PORT> -U krz_worker -d krz_attend -X -v ON_ERROR_STOP=1 -f database/scripts/negative_tests.sql
-- Everything runs inside one transaction that is rolled back, with fixed test codes (no sequence is consumed and
-- no audit row is written). Each block raises if the refusal it expects does NOT happen.
\set ON_ERROR_STOP on
SELECT current_user = 'krz_worker' AS is_worker \gset
BEGIN;

\if :is_worker
DO $$
BEGIN
  PERFORM 1 FROM attend.employee LIMIT 1;
  RAISE EXCEPTION 'NOT REFUSED: krz_worker selected from employee';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok    krz_worker cannot select employee';
END $$;

DO $$
BEGIN
  PERFORM 1 FROM attend.crossing LIMIT 1;
  RAISE EXCEPTION 'NOT REFUSED: krz_worker selected from crossing';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok    krz_worker cannot select crossing';
END $$;

DO $$
BEGIN
  PERFORM attend.purge_audit(now() - interval '400 days');
  RAISE EXCEPTION 'NOT REFUSED: krz_worker executed purge_audit';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok    krz_worker cannot execute purge_audit';
END $$;

DO $$
BEGIN
  PERFORM 1 FROM attend.v_worker_config_version;
  RAISE NOTICE 'ok    krz_worker can read v_worker_config_version';
END $$;

\else
DO $$
BEGIN
  UPDATE attend.audit_log SET action = action;
  RAISE EXCEPTION 'NOT REFUSED: krz_app updated audit_log';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok    krz_app cannot update audit_log';
END $$;

DO $$
BEGIN
  DELETE FROM attend.audit_log;
  RAISE EXCEPTION 'NOT REFUSED: krz_app deleted from audit_log';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok    krz_app cannot delete from audit_log';
END $$;

DO $$
BEGIN
  PERFORM attend.purge_audit(now());
  RAISE EXCEPTION 'NOT REFUSED: purge_audit(now()) deleted recent entries';
EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok    purge_audit(now()) refused by the 30-day floor';
END $$;

DO $$
DECLARE
  test_employee uuid;
  general_shift bigint;
BEGIN
  INSERT INTO attend.employee (entra_tenant_id, entra_object_id, display_name, upn, account_enabled)
  VALUES (gen_random_uuid(), gen_random_uuid(), 'Negative Test', 'negative.test@invalid.example', true)
  RETURNING id INTO test_employee;
  SELECT id INTO general_shift FROM attend.shift WHERE is_default;
  INSERT INTO attend.shift_allocation (code, shift_id, target_type, employee_id, valid_from, valid_until)
  VALUES ('ALC-9901', general_shift, 'EMPLOYEE', test_employee, DATE '2026-01-01', NULL);
  BEGIN
    INSERT INTO attend.shift_allocation (code, shift_id, target_type, employee_id, valid_from, valid_until)
    VALUES ('ALC-9902', general_shift, 'EMPLOYEE', test_employee, DATE '2026-06-01', DATE '2026-06-30');
    RAISE EXCEPTION 'NOT REFUSED: overlapping allocations for one employee';
  EXCEPTION WHEN exclusion_violation THEN RAISE NOTICE 'ok    overlapping allocations rejected';
  END;
  -- A cancelled allocation does not count.
  UPDATE attend.shift_allocation SET cancelled_at = now(), cancelled_by = test_employee WHERE code = 'ALC-9901';
  INSERT INTO attend.shift_allocation (code, shift_id, target_type, employee_id, valid_from, valid_until)
  VALUES ('ALC-9902', general_shift, 'EMPLOYEE', test_employee, DATE '2026-06-01', DATE '2026-06-30');
  RAISE NOTICE 'ok    a cancelled allocation does not block a new one';
END $$;

DO $$
BEGIN
  INSERT INTO attend.shift (code, name, colour, is_default) VALUES ('NEGTEST', 'Negative test shift', '#000000', true);
  RAISE EXCEPTION 'NOT REFUSED: a second default shift';
EXCEPTION WHEN unique_violation THEN RAISE NOTICE 'ok    a second default shift rejected';
END $$;

DO $$
BEGIN
  INSERT INTO attend.threshold_profile (code, profile_values, active) VALUES ('TP-9901', '{"accept": 0.5}', true);
  RAISE EXCEPTION 'NOT REFUSED: a second active threshold profile';
EXCEPTION WHEN unique_violation THEN RAISE NOTICE 'ok    a second active threshold profile rejected';
END $$;

DO $$
BEGIN
  UPDATE attend.threshold_profile SET profile_values = profile_values || '{"accept": 0.99}' WHERE active;
  RAISE EXCEPTION 'NOT REFUSED: threshold profile values changed';
EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok    threshold profiles are immutable';
END $$;

DO $$
BEGIN
  CREATE TABLE attend.negative_test_ddl (id int);
  RAISE EXCEPTION 'NOT REFUSED: krz_app created a table';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok    krz_app cannot run DDL in schema attend';
END $$;
\endif

ROLLBACK;
\echo 'negative_tests.sql: all expected refusals happened'

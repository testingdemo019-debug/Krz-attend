-- V011__privacy_audit_settings.sql
-- Purpose: privacy requests, the append-only tamper-evident audit log, application settings and ShedLock.
-- Tables: privacy_request, audit_log, app_setting, shedlock.
-- Functions: audit_canonical_text(audit_log), audit_log_chain(), audit_log_append_only(), purge_audit(timestamptz).

-- ---------------------------------------------------------------------------------------------------------
-- privacy_request — PR-nnn, due 30 days after receipt by default (the backend sets due_on).
CREATE TABLE attend.privacy_request (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code         text NOT NULL UNIQUE CHECK (code ~ '^PR-\d{3,}$'),
  employee_id  uuid NOT NULL,
  type         text NOT NULL CHECK (type IN ('ACCESS', 'CORRECTION', 'ERASURE')),
  status       text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'DECLINED')),
  received_on  date NOT NULL,
  due_on       date NOT NULL,
  note         text CHECK (length(note) <= 1000),
  handled_by   uuid,
  completed_at timestamptz,
  outcome      text CHECK (length(outcome) <= 1000),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  version      integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_privacy_request_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_privacy_request_handled_by FOREIGN KEY (handled_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_privacy_request_due CHECK (due_on >= received_on),
  CONSTRAINT ck_privacy_request_completed CHECK ((status IN ('COMPLETED', 'DECLINED')) = (completed_at IS NOT NULL))
);
CREATE INDEX ix_privacy_request_employee_id ON attend.privacy_request (employee_id);
CREATE INDEX ix_privacy_request_handled_by ON attend.privacy_request (handled_by);
CREATE INDEX ix_privacy_request_status_due ON attend.privacy_request (status, due_on);
CREATE TRIGGER trg_privacy_request_updated_at BEFORE UPDATE ON attend.privacy_request
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- ---------------------------------------------------------------------------------------------------------
-- audit_log — append-only and hash-chained (security §9). The backend writes it through AuditService in the
-- same transaction as the change. Secrets are never written into before/after values.
--
-- The chain trigger takes the chain lock FIRST and only then assigns the AU code, so the code number is the
-- position in the chain: row_hash = sha256(prev_hash || canonical text), prev_hash = row_hash of the previous
-- code. (The identity id is assigned before triggers run, so concurrent inserts could chain out of id order.)
CREATE TABLE attend.audit_log (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code         text NOT NULL UNIQUE CHECK (code ~ '^AU-\d{6,}$'),
  occurred_at  timestamptz NOT NULL DEFAULT now(),
  actor_id     uuid,
  actor_label  text NOT NULL CHECK (length(actor_label) <= 200),
  actor_roles  text CHECK (length(actor_roles) <= 500),
  actor_ip     inet,
  category     text NOT NULL CHECK (category IN ('SIGN_IN', 'USERS_ROLES', 'ORGANISATION', 'EMPLOYEES', 'ENROLMENT', 'REVIEW',
                                                 'ATTENDANCE', 'CAMERAS', 'RECOGNITION', 'SETTINGS', 'HRMS', 'PRIVACY',
                                                 'EXPORT', 'LIVE_VIEW', 'SYSTEM')),
  action       text NOT NULL CHECK (length(action) <= 200),
  target       text CHECK (length(target) <= 500),
  before_value text CHECK (length(before_value) <= 4000),
  after_value  text CHECK (length(after_value) <= 4000),
  details      jsonb,
  trace_id     text CHECK (length(trace_id) <= 64),
  prev_hash    bytea,
  row_hash     bytea NOT NULL,
  CONSTRAINT fk_audit_log_actor_id FOREIGN KEY (actor_id) REFERENCES attend.employee (id)
);
CREATE INDEX ix_audit_log_occurred_at ON attend.audit_log (occurred_at DESC);
CREATE INDEX ix_audit_log_category ON attend.audit_log (category, occurred_at DESC);
CREATE INDEX ix_audit_log_actor ON attend.audit_log (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_log_chain_position ON attend.audit_log ((substring(code FROM 4)::bigint));

-- Deterministic text of a row: every field is "<length>:<value>" ("-" for NULL), separated by U+001F, with the
-- timestamp in UTC so the result does not depend on the session time zone.
CREATE FUNCTION attend.audit_canonical_text(r attend.audit_log) RETURNS text
LANGUAGE sql STABLE AS $$
  SELECT array_to_string(ARRAY[
    coalesce(length(r.code)::text || ':' || r.code, '-'),
    coalesce('27:' || to_char(r.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '-'),
    coalesce('36:' || r.actor_id::text, '-'),
    coalesce(length(r.actor_label)::text || ':' || r.actor_label, '-'),
    coalesce(length(r.actor_roles)::text || ':' || r.actor_roles, '-'),
    coalesce(length(r.actor_ip::text)::text || ':' || r.actor_ip::text, '-'),
    coalesce(length(r.category)::text || ':' || r.category, '-'),
    coalesce(length(r.action)::text || ':' || r.action, '-'),
    coalesce(length(r.target)::text || ':' || r.target, '-'),
    coalesce(length(r.before_value)::text || ':' || r.before_value, '-'),
    coalesce(length(r.after_value)::text || ':' || r.after_value, '-'),
    coalesce(length(r.details::text)::text || ':' || r.details::text, '-'),
    coalesce(length(r.trace_id)::text || ':' || r.trace_id, '-')
  ], E'\x1f')
$$;
GRANT EXECUTE ON FUNCTION attend.audit_canonical_text(attend.audit_log) TO krz_app;

CREATE FUNCTION attend.audit_log_chain() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  last_hash bytea;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('attend.audit_chain'));
  NEW.code := attend.next_code('AU-', 'attend.seq_audit_code', 6);
  SELECT a.row_hash INTO last_hash
    FROM attend.audit_log AS a
   ORDER BY substring(a.code FROM 4)::bigint DESC
   LIMIT 1;
  NEW.prev_hash := last_hash;  -- NULL for the genesis row
  NEW.row_hash := sha256(coalesce(last_hash, '\x'::bytea) || convert_to(attend.audit_canonical_text(NEW), 'UTF8'));
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_audit_log_chain BEFORE INSERT ON attend.audit_log
  FOR EACH ROW EXECUTE FUNCTION attend.audit_log_chain();

-- UPDATE is always refused; DELETE only inside purge_audit (which sets attend.allow_audit_purge for its own
-- transaction); TRUNCATE always refused.
CREATE FUNCTION attend.audit_log_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' AND current_setting('attend.allow_audit_purge', true) = 'on' THEN
    RETURN OLD;
  END IF;
  RAISE EXCEPTION 'audit_log is append-only (% refused)', TG_OP USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON attend.audit_log
  FOR EACH ROW EXECUTE FUNCTION attend.audit_log_append_only();
CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON attend.audit_log
  FOR EACH ROW EXECUTE FUNCTION attend.audit_log_append_only();
CREATE TRIGGER trg_audit_log_no_truncate BEFORE TRUNCATE ON attend.audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION attend.audit_log_append_only();

-- Retention purge (R17): deletes a prefix of the chain — every entry up to the last one that occurred before
-- the cut-off — so the remaining rows still chain from the oldest one. Hard floor: never newer than 30 days.
CREATE FUNCTION attend.purge_audit(older_than timestamptz) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = attend, pg_temp AS $$
DECLARE
  last_position bigint;
  deleted       bigint;
BEGIN
  IF older_than IS NULL OR older_than > now() - interval '30 days' THEN
    RAISE EXCEPTION 'purge_audit refuses to delete audit entries from the last 30 days (requested cut-off %)', older_than
      USING ERRCODE = 'check_violation';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext('attend.audit_chain'));
  SELECT max(substring(code FROM 4)::bigint) INTO last_position FROM attend.audit_log WHERE occurred_at < older_than;
  IF last_position IS NULL THEN
    RETURN 0;
  END IF;
  PERFORM set_config('attend.allow_audit_purge', 'on', true);
  DELETE FROM attend.audit_log WHERE substring(code FROM 4)::bigint <= last_position;
  GET DIAGNOSTICS deleted = ROW_COUNT;
  PERFORM set_config('attend.allow_audit_purge', 'off', true);
  RETURN deleted;
END
$$;
REVOKE EXECUTE ON FUNCTION attend.purge_audit(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION attend.purge_audit(timestamptz) TO krz_app;

REVOKE UPDATE, DELETE, TRUNCATE ON attend.audit_log FROM krz_app;

-- ---------------------------------------------------------------------------------------------------------
-- app_setting — one JSON document per settings area (general, signin, attendanceRules, enrolment, privacy, hrms).
-- The backend SettingsService merges these overrides over the defaults in application.yml.
CREATE TABLE attend.app_setting (
  key        text PRIMARY KEY CHECK (key ~ '^[a-zA-Z]{2,40}$'),
  value      jsonb NOT NULL CHECK (jsonb_typeof(value) = 'object'),
  version    integer NOT NULL DEFAULT 0,
  updated_by uuid,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_app_setting_updated_by FOREIGN KEY (updated_by) REFERENCES attend.employee (id)
);
CREATE INDEX ix_app_setting_updated_by ON attend.app_setting (updated_by);
CREATE TRIGGER trg_app_setting_updated_at BEFORE UPDATE ON attend.app_setting
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- shedlock — ShedLock's JDBC provider schema (its own fixed layout; no surrogate key).
CREATE TABLE attend.shedlock (
  name       varchar(64) PRIMARY KEY,
  lock_until timestamptz NOT NULL,
  locked_at  timestamptz NOT NULL,
  locked_by  varchar(255) NOT NULL
);

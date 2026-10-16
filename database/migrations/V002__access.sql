-- V002__access.sql
-- Purpose: identity (employees from Microsoft Entra ID), permissions, roles, user access, access requests and
-- directory sync bookkeeping.
-- Tables: employee, permission, role, role_permission, user_access, user_role, access_request,
--         directory_sync_run, directory_sync_state.

-- ---------------------------------------------------------------------------------------------------------
-- employee — identity only. The user key is (entra_tenant_id, entra_object_id), never e-mail or UPN.
CREATE TABLE attend.employee (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  entra_tenant_id  uuid NOT NULL,
  entra_object_id  uuid NOT NULL,
  display_name     text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 256),
  given_name       text CHECK (length(given_name) <= 128),
  surname          text CHECK (length(surname) <= 128),
  upn              citext NOT NULL UNIQUE CHECK (length(upn) <= 254),
  mail             citext CHECK (length(mail) <= 254),
  account_enabled  boolean NOT NULL,
  disabled_reason  text CHECK (length(disabled_reason) <= 200),
  left_on          date,
  last_sign_in_at  timestamptz,
  synced_at        timestamptz,
  source           text NOT NULL DEFAULT 'ENTRA' CHECK (source IN ('ENTRA')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  version          integer NOT NULL DEFAULT 0,
  CONSTRAINT uq_employee_entra_identity UNIQUE (entra_tenant_id, entra_object_id)
);
CREATE TRIGGER trg_employee_updated_at BEFORE UPDATE ON attend.employee
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- ---------------------------------------------------------------------------------------------------------
-- permission — the 19 keys (seeded by R__010; the backend Permission enum is the source of truth in code).
CREATE TABLE attend.permission (
  key         text PRIMARY KEY CHECK (key ~ '^[a-z]+(\.[a-z]+)+$'),
  group_name  text NOT NULL CHECK (group_name IN ('Monitoring', 'Employees', 'Attendance', 'Settings')),
  label       text NOT NULL,
  description text NOT NULL,
  sort_order  smallint NOT NULL
);

-- ---------------------------------------------------------------------------------------------------------
-- role — owned by KRZ Attend (never taken from Entra). Built-in roles cannot be deleted; locked ones cannot be edited.
CREATE TABLE attend.role (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code             text NOT NULL UNIQUE CHECK (code ~ '^ROLE-[A-Z0-9-]{2,30}$'),
  name             citext NOT NULL UNIQUE CHECK (length(name) BETWEEN 2 AND 60),
  description      text NOT NULL DEFAULT '' CHECK (length(description) <= 500),
  built_in         boolean NOT NULL DEFAULT false,
  locked           boolean NOT NULL DEFAULT false,
  created_by       uuid,
  created_by_label text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  version          integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_role_created_by FOREIGN KEY (created_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_role_locked_is_built_in CHECK (NOT locked OR built_in)
);
CREATE INDEX ix_role_created_by ON attend.role (created_by);
CREATE TRIGGER trg_role_updated_at BEFORE UPDATE ON attend.role
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- Rows belong to their role, so they go with it.
CREATE TABLE attend.role_permission (
  role_id        bigint NOT NULL,
  permission_key text NOT NULL,
  PRIMARY KEY (role_id, permission_key),
  CONSTRAINT fk_role_permission_role_id FOREIGN KEY (role_id) REFERENCES attend.role (id) ON DELETE CASCADE,
  CONSTRAINT fk_role_permission_permission_key FOREIGN KEY (permission_key) REFERENCES attend.permission (key)
);
CREATE INDEX ix_role_permission_permission_key ON attend.role_permission (permission_key);

-- ---------------------------------------------------------------------------------------------------------
-- user_access — the people who have access to KRZ Attend (one row per user; roles in user_role).
CREATE TABLE attend.user_access (
  employee_id    uuid PRIMARY KEY,
  added_by       uuid,
  added_by_label text,
  added_at       timestamptz NOT NULL DEFAULT now(),
  note           text CHECK (length(note) <= 500),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  version        integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_user_access_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_user_access_added_by FOREIGN KEY (added_by) REFERENCES attend.employee (id)
);
CREATE INDEX ix_user_access_added_by ON attend.user_access (added_by);
CREATE TRIGGER trg_user_access_updated_at BEFORE UPDATE ON attend.user_access
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- Roles exist only for users with access: removing access removes the role rows. Deleting a role that is still
-- assigned is refused (the backend unassigns it deliberately first).
CREATE TABLE attend.user_role (
  employee_id uuid NOT NULL,
  role_id     bigint NOT NULL,
  PRIMARY KEY (employee_id, role_id),
  CONSTRAINT fk_user_role_employee_id FOREIGN KEY (employee_id) REFERENCES attend.user_access (employee_id) ON DELETE CASCADE,
  CONSTRAINT fk_user_role_role_id FOREIGN KEY (role_id) REFERENCES attend.role (id)
);
CREATE INDEX ix_user_role_role_id ON attend.user_role (role_id);

-- ---------------------------------------------------------------------------------------------------------
-- access_request — submitted by signed-in users without a role (NR-06). One pending request per employee.
CREATE TABLE attend.access_request (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code            text NOT NULL UNIQUE CHECK (code ~ '^AR-\d{4,}$'),
  employee_id     uuid NOT NULL,
  note            text CHECK (length(note) <= 500),
  status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED')),
  decided_by      uuid,
  decided_at      timestamptz,
  decision_reason text CHECK (length(decision_reason) <= 500),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  version         integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_access_request_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_access_request_decided_by FOREIGN KEY (decided_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_access_request_decision CHECK ((status = 'PENDING') = (decided_at IS NULL))
);
CREATE INDEX ix_access_request_employee_id ON attend.access_request (employee_id);
CREATE INDEX ix_access_request_decided_by ON attend.access_request (decided_by);
CREATE INDEX ix_access_request_status ON attend.access_request (status, created_at DESC);
CREATE UNIQUE INDEX uq_access_request_one_pending ON attend.access_request (employee_id) WHERE status = 'PENDING';
CREATE TRIGGER trg_access_request_updated_at BEFORE UPDATE ON attend.access_request
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- ---------------------------------------------------------------------------------------------------------
-- directory_sync_run — one row per Microsoft Graph sync (SY-n). status is NULL while the run is in progress.
CREATE TABLE attend.directory_sync_run (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code         text NOT NULL UNIQUE CHECK (code ~ '^SY-\d+$'),
  started_at   timestamptz NOT NULL DEFAULT now(),
  finished_at  timestamptz,
  trigger      text NOT NULL CHECK (trigger IN ('SCHEDULE', 'MANUAL')),
  triggered_by uuid,
  full_sync    boolean NOT NULL DEFAULT false,
  added        integer NOT NULL DEFAULT 0 CHECK (added >= 0),
  updated      integer NOT NULL DEFAULT 0 CHECK (updated >= 0),
  disabled     integer NOT NULL DEFAULT 0 CHECK (disabled >= 0),
  total        integer NOT NULL DEFAULT 0 CHECK (total >= 0),
  status       text CHECK (status IN ('SUCCESS', 'WARNING', 'FAILED')),
  warnings     jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(warnings) = 'array'),
  error        text,
  duration_ms  integer CHECK (duration_ms >= 0),
  CONSTRAINT fk_directory_sync_run_triggered_by FOREIGN KEY (triggered_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_directory_sync_run_finished CHECK ((finished_at IS NULL) = (status IS NULL))
);
CREATE INDEX ix_directory_sync_run_triggered_by ON attend.directory_sync_run (triggered_by);
CREATE INDEX ix_directory_sync_run_started_at ON attend.directory_sync_run (started_at DESC);

-- directory_sync_state — the Graph users/delta link and the time of the last full re-sync (single row).
CREATE TABLE attend.directory_sync_state (
  id                smallint PRIMARY KEY CHECK (id = 1),
  delta_link        text,
  last_full_sync_at timestamptz,
  updated_at        timestamptz NOT NULL DEFAULT now()
);
INSERT INTO attend.directory_sync_state (id) VALUES (1);
CREATE TRIGGER trg_directory_sync_state_updated_at BEFORE UPDATE ON attend.directory_sync_state
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

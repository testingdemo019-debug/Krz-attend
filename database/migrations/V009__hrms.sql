-- V009__hrms.sql
-- Purpose: the KRZ HRMS outbox (R8). While HRMS_ENABLED=false punches are held as HELD_NOT_CONNECTED (PENDING P-07).
-- Tables: hrms_outbox.

CREATE TABLE attend.hrms_outbox (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code            text NOT NULL UNIQUE CHECK (code ~ '^OB-\d{6,}$'),
  kind            text NOT NULL CHECK (kind IN ('PUNCH', 'VOID')),
  punch_id        text NOT NULL CHECK (punch_id ~ '^PU-\d{6,}(-OUT)?$'),
  employee_id     uuid NOT NULL,
  shift_day       date NOT NULL,
  crossing_id     bigint,
  payload         jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  status          text NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'VOIDED', 'BLOCKED_NO_EMPLOYEE_ID', 'HELD_NOT_CONNECTED')),
  attempts        smallint NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  last_error      text CHECK (length(last_error) <= 1000),
  last_attempt_at timestamptz,
  next_attempt_at timestamptz,
  due_at          timestamptz NOT NULL,
  sent_at         timestamptz,
  voided_at       timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  version         integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_hrms_outbox_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_hrms_outbox_crossing_id FOREIGN KEY (crossing_id) REFERENCES attend.crossing (id) ON DELETE RESTRICT,
  CONSTRAINT ck_hrms_outbox_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL) OR status = 'VOIDED'),
  CONSTRAINT ck_hrms_outbox_voided CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL))
);
-- One live punch per punch ID; a voided punch may be re-created.
CREATE UNIQUE INDEX uq_hrms_outbox_live_punch ON attend.hrms_outbox (punch_id) WHERE kind = 'PUNCH' AND status <> 'VOIDED';
CREATE INDEX ix_hrms_outbox_status_next_attempt ON attend.hrms_outbox (status, next_attempt_at);
CREATE INDEX ix_hrms_outbox_employee_day ON attend.hrms_outbox (employee_id, shift_day);
CREATE INDEX ix_hrms_outbox_crossing_id ON attend.hrms_outbox (crossing_id);
CREATE TRIGGER trg_hrms_outbox_updated_at BEFORE UPDATE ON attend.hrms_outbox
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

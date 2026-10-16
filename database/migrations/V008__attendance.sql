-- V008__attendance.sql
-- Purpose: materialised attendance per (employee, shift day) computed by the backend's punch rules engine (R7),
-- and the day-close runs (R13).
-- Tables: attendance_day, day_close_run.

-- attendance_day — recomputed in the same transaction as every change that affects it. status is NULL when the
-- day is not applicable (future, before join_date, after left_on).
CREATE TABLE attend.attendance_day (
  employee_id           uuid NOT NULL,
  shift_day             date NOT NULL,
  shift_id              bigint NOT NULL,
  shift_version_id      bigint NOT NULL,
  allocation_id         bigint,
  shift_source          text NOT NULL CHECK (shift_source IN ('EMPLOYEE', 'DEPARTMENT', 'DEFAULT')),
  window_start          timestamptz NOT NULL,
  shift_start           timestamptz NOT NULL,
  shift_end             timestamptz NOT NULL,
  cutoff_at             timestamptz NOT NULL,
  close_at              timestamptz NOT NULL,
  tracked               boolean NOT NULL,
  tracking_reason       text CHECK (tracking_reason IN ('NO_FACE', 'NO_CAMERA', 'EXEMPT')),
  holiday_id            bigint,
  weekly_off            boolean NOT NULL DEFAULT false,
  status                text CHECK (status IN ('INSIDE', 'STEPPED_OUT', 'CHECKED_OUT', 'LATE', 'EARLY_EXIT', 'HALF_DAY',
                                               'MISSING_CHECK_OUT', 'MISSING_CHECK_IN', 'NOT_YET_IN', 'ABSENT', 'HOLIDAY',
                                               'WEEKLY_OFF', 'WORKED_ON_HOLIDAY', 'WORKED_ON_WEEKLY_OFF', 'NOT_TRACKED')),
  status_code           text CHECK (status_code IN ('P', 'L', 'EE', 'HD', 'MO', 'MI', '·', 'A', 'H', 'WO', 'WH', 'WW', 'NT')),
  check_in_crossing_id  bigint,
  check_out_crossing_id bigint,
  check_out_final       boolean NOT NULL DEFAULT false,
  inside                boolean NOT NULL DEFAULT false,
  stepped_out           boolean NOT NULL DEFAULT false,
  late                  boolean NOT NULL DEFAULT false,
  late_minutes          integer CHECK (late_minutes >= 0),
  early_exit            boolean NOT NULL DEFAULT false,
  missing_check_out     boolean NOT NULL DEFAULT false,
  worked_minutes        integer CHECK (worked_minutes >= 0),
  last_movement_at      timestamptz,
  finalised             boolean NOT NULL DEFAULT false,
  computed_at           timestamptz NOT NULL DEFAULT now(),
  rules_hash            text,
  PRIMARY KEY (employee_id, shift_day),
  CONSTRAINT fk_attendance_day_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_attendance_day_shift_id FOREIGN KEY (shift_id) REFERENCES attend.shift (id),
  CONSTRAINT fk_attendance_day_shift_version_id FOREIGN KEY (shift_version_id) REFERENCES attend.shift_version (id),
  CONSTRAINT fk_attendance_day_allocation_id FOREIGN KEY (allocation_id) REFERENCES attend.shift_allocation (id),
  -- A deleted holiday must not block the delete; the recompute that follows clears the day.
  CONSTRAINT fk_attendance_day_holiday_id FOREIGN KEY (holiday_id) REFERENCES attend.holiday (id) ON DELETE SET NULL,
  CONSTRAINT fk_attendance_day_check_in_crossing_id FOREIGN KEY (check_in_crossing_id) REFERENCES attend.crossing (id) ON DELETE RESTRICT,
  CONSTRAINT fk_attendance_day_check_out_crossing_id FOREIGN KEY (check_out_crossing_id) REFERENCES attend.crossing (id) ON DELETE RESTRICT,
  CONSTRAINT ck_attendance_day_status_code CHECK ((status IS NULL) = (status_code IS NULL)),
  CONSTRAINT ck_attendance_day_times CHECK (window_start <= shift_start AND shift_start < shift_end AND shift_end < close_at)
);
CREATE INDEX ix_attendance_day_day_status ON attend.attendance_day (shift_day, status);
CREATE INDEX ix_attendance_day_inside ON attend.attendance_day (shift_day) WHERE inside;
CREATE INDEX ix_attendance_day_shift_id ON attend.attendance_day (shift_id);
CREATE INDEX ix_attendance_day_shift_version_id ON attend.attendance_day (shift_version_id);
CREATE INDEX ix_attendance_day_allocation_id ON attend.attendance_day (allocation_id);
CREATE INDEX ix_attendance_day_holiday_id ON attend.attendance_day (holiday_id);
CREATE INDEX ix_attendance_day_check_in_crossing_id ON attend.attendance_day (check_in_crossing_id);
CREATE INDEX ix_attendance_day_check_out_crossing_id ON attend.attendance_day (check_out_crossing_id);

-- day_close_run — one per (shift, shift day): DC-<yyyy-mm-dd>-<shift code>. Idempotent by the unique key.
CREATE TABLE attend.day_close_run (
  id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code               text NOT NULL UNIQUE CHECK (code ~ '^DC-\d{4}-\d{2}-\d{2}-[A-Z0-9-]{2,8}$'),
  shift_id           bigint NOT NULL,
  shift_day          date NOT NULL,
  ran_at             timestamptz NOT NULL DEFAULT now(),
  trigger            text NOT NULL CHECK (trigger IN ('SCHEDULE', 'MANUAL')),
  triggered_by       uuid,
  employees          integer NOT NULL DEFAULT 0 CHECK (employees >= 0),
  finalised          integer NOT NULL DEFAULT 0 CHECK (finalised >= 0),
  absent             integer NOT NULL DEFAULT 0 CHECK (absent >= 0),
  missing_check_outs integer NOT NULL DEFAULT 0 CHECK (missing_check_outs >= 0),
  CONSTRAINT fk_day_close_run_shift_id FOREIGN KEY (shift_id) REFERENCES attend.shift (id),
  CONSTRAINT fk_day_close_run_triggered_by FOREIGN KEY (triggered_by) REFERENCES attend.employee (id),
  CONSTRAINT uq_day_close_run_shift_day UNIQUE (shift_id, shift_day)
);
CREATE INDEX ix_day_close_run_shift_day ON attend.day_close_run (shift_day DESC);
CREATE INDEX ix_day_close_run_triggered_by ON attend.day_close_run (triggered_by);

-- V003__organisation.sql
-- Purpose: organisation master data, managed locally until KRZ HRMS provides it (PENDING P-08).
-- Tables: location, entry_point, department, shift, shift_version, shift_allocation, holiday.
-- Function: is_weekday_set(smallint[]).

-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE attend.location (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code       text NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9]{2,6}$'),
  name       citext NOT NULL UNIQUE CHECK (length(name) BETWEEN 2 AND 80),
  address    text CHECK (length(address) <= 300),
  status     text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  source     text NOT NULL DEFAULT 'LOCAL' CHECK (source IN ('LOCAL', 'CSV_IMPORT', 'HRMS')),
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version    integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_location_created_by FOREIGN KEY (created_by) REFERENCES attend.employee (id)
);
CREATE INDEX ix_location_created_by ON attend.location (created_by);
CREATE TRIGGER trg_location_updated_at BEFORE UPDATE ON attend.location
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

CREATE TABLE attend.entry_point (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        text NOT NULL UNIQUE CHECK (code ~ '^EP-\d{2,}$'),
  location_id bigint NOT NULL,
  name        text NOT NULL CHECK (length(name) BETWEEN 2 AND 80),
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  version     integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_entry_point_location_id FOREIGN KEY (location_id) REFERENCES attend.location (id),
  CONSTRAINT uq_entry_point_location_name UNIQUE (location_id, name)
);
CREATE TRIGGER trg_entry_point_updated_at BEFORE UPDATE ON attend.entry_point
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE attend.department (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        text NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9]{2,6}$'),
  name        citext NOT NULL UNIQUE CHECK (length(name) BETWEEN 2 AND 80),
  description text CHECK (length(description) <= 500),
  head_id     uuid,
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  source      text NOT NULL DEFAULT 'LOCAL' CHECK (source IN ('LOCAL', 'CSV_IMPORT', 'HRMS')),
  created_by  uuid,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  version     integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_department_head_id FOREIGN KEY (head_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_department_created_by FOREIGN KEY (created_by) REFERENCES attend.employee (id)
);
CREATE INDEX ix_department_head_id ON attend.department (head_id);
CREATE INDEX ix_department_created_by ON attend.department (created_by);
CREATE TRIGGER trg_department_updated_at BEFORE UPDATE ON attend.department
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- ---------------------------------------------------------------------------------------------------------
-- Shifts. Exactly one default shift exists at all times (R1); the partial unique index allows at most one and
-- the reference seed (R__070) creates the first. The default shift can never be archived.
CREATE TABLE attend.shift (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code       text NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9-]{2,8}$'),
  name       citext NOT NULL UNIQUE CHECK (length(name) BETWEEN 2 AND 60),
  colour     text NOT NULL CHECK (colour ~ '^#[0-9a-f]{6}$'),
  status     text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  source     text NOT NULL DEFAULT 'LOCAL' CHECK (source IN ('LOCAL', 'CSV_IMPORT', 'HRMS')),
  is_default boolean NOT NULL DEFAULT false,
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version    integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_shift_created_by FOREIGN KEY (created_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_shift_default_not_archived CHECK (NOT (is_default AND status = 'ARCHIVED'))
);
CREATE UNIQUE INDEX uq_shift_single_default ON attend.shift (is_default) WHERE is_default;
CREATE INDEX ix_shift_created_by ON attend.shift (created_by);
CREATE TRIGGER trg_shift_updated_at BEFORE UPDATE ON attend.shift
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- Weekly offs: weekday numbers 0 (Sunday) … 6 (Saturday), no duplicates, at least one working day.
CREATE FUNCTION attend.is_weekday_set(days smallint[]) RETURNS boolean
LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT cardinality(days) <= 6
     AND days <@ ARRAY[0, 1, 2, 3, 4, 5, 6]::smallint[]
     AND cardinality(days) = (SELECT count(DISTINCT d) FROM unnest(days) AS d)
$$;
GRANT EXECUTE ON FUNCTION attend.is_weekday_set(smallint[]) TO krz_app;

-- Editing timings creates a new version effective from a date (R10); past days keep their version.
-- Duration = end - start, +24 h when end <= start (night shift). Validation from R2.
CREATE TABLE attend.shift_version (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  shift_id             bigint NOT NULL,
  effective_from       date NOT NULL,
  start_time           time NOT NULL,
  end_time             time NOT NULL,
  break_minutes        smallint NOT NULL CHECK (break_minutes >= 0),
  weekly_offs          smallint[] NOT NULL CHECK (attend.is_weekday_set(weekly_offs)),
  late_grace_min       smallint NOT NULL CHECK (late_grace_min BETWEEN 0 AND 120),
  early_grace_min      smallint NOT NULL CHECK (early_grace_min BETWEEN 0 AND 120),
  half_day_below_hours numeric(4,2) NOT NULL CHECK (half_day_below_hours > 0),
  absent_below_hours   numeric(4,2) NOT NULL CHECK (absent_below_hours >= 0),
  window_before_hours  numeric(4,2) NOT NULL CHECK (window_before_hours BETWEEN 1 AND 6),
  day_close_hours      numeric(4,2) NOT NULL CHECK (day_close_hours BETWEEN 1 AND 8),
  note                 text CHECK (length(note) <= 500),
  edited_by            uuid,
  edited_by_label      text,
  edited_at            timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_shift_version_shift_id FOREIGN KEY (shift_id) REFERENCES attend.shift (id),
  CONSTRAINT fk_shift_version_edited_by FOREIGN KEY (edited_by) REFERENCES attend.employee (id),
  CONSTRAINT uq_shift_version_effective UNIQUE (shift_id, effective_from),
  CONSTRAINT ck_shift_version_times CHECK (start_time <> end_time),
  CONSTRAINT ck_shift_version_absent_below_half_day CHECK (absent_below_hours < half_day_below_hours),
  CONSTRAINT ck_shift_version_half_day_below_duration CHECK (
    half_day_below_hours * 60 < extract(epoch FROM (end_time - start_time)) / 60
                                + CASE WHEN end_time <= start_time THEN 1440 ELSE 0 END),
  CONSTRAINT ck_shift_version_break_below_duration CHECK (
    break_minutes < extract(epoch FROM (end_time - start_time)) / 60
                    + CASE WHEN end_time <= start_time THEN 1440 ELSE 0 END),
  -- One shift day's window [start - window_before, end + day_close) must not reach the next day's window.
  CONSTRAINT ck_shift_version_window_fits_day CHECK (
    window_before_hours * 60 + day_close_hours * 60
      + extract(epoch FROM (end_time - start_time)) / 60
      + CASE WHEN end_time <= start_time THEN 1440 ELSE 0 END <= 1440)
);
CREATE INDEX ix_shift_version_edited_by ON attend.shift_version (edited_by);

-- ---------------------------------------------------------------------------------------------------------
-- Shift allocation (R9). Precedence Employee > Department > Organisation default. The exclusion constraint is
-- the last line of defence against two live allocations for the same target overlapping in time
-- (a NULL valid_until is an open end). The backend maps its violation to ALLOCATION_OVERLAP.
CREATE TABLE attend.shift_allocation (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code             text NOT NULL UNIQUE CHECK (code ~ '^ALC-\d{4,}$'),
  shift_id         bigint NOT NULL,
  target_type      text NOT NULL CHECK (target_type IN ('EMPLOYEE', 'DEPARTMENT')),
  employee_id      uuid,
  department_id    bigint,
  valid_from       date NOT NULL,
  valid_until      date,
  cancelled_at     timestamptz,
  cancelled_by     uuid,
  note             text CHECK (length(note) <= 500),
  created_by       uuid,
  created_by_label text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  version          integer NOT NULL DEFAULT 0,
  target_key       text GENERATED ALWAYS AS (coalesce('E:' || employee_id::text, 'D:' || department_id::text)) STORED,
  CONSTRAINT fk_shift_allocation_shift_id FOREIGN KEY (shift_id) REFERENCES attend.shift (id),
  CONSTRAINT fk_shift_allocation_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_shift_allocation_department_id FOREIGN KEY (department_id) REFERENCES attend.department (id),
  CONSTRAINT fk_shift_allocation_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES attend.employee (id),
  CONSTRAINT fk_shift_allocation_created_by FOREIGN KEY (created_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_shift_allocation_target CHECK (
    (target_type = 'EMPLOYEE') = (employee_id IS NOT NULL)
    AND (target_type = 'DEPARTMENT') = (department_id IS NOT NULL)),
  CONSTRAINT ck_shift_allocation_range CHECK (valid_until >= valid_from),
  CONSTRAINT ex_shift_allocation_overlap EXCLUDE USING gist (
    target_key WITH =,
    daterange(valid_from, valid_until, '[]') WITH &&
  ) WHERE (cancelled_at IS NULL)
);
CREATE INDEX ix_shift_allocation_shift_id ON attend.shift_allocation (shift_id);
CREATE INDEX ix_shift_allocation_employee_id ON attend.shift_allocation (employee_id, valid_from);
CREATE INDEX ix_shift_allocation_department_id ON attend.shift_allocation (department_id, valid_from);
CREATE INDEX ix_shift_allocation_cancelled_by ON attend.shift_allocation (cancelled_by);
CREATE INDEX ix_shift_allocation_created_by ON attend.shift_allocation (created_by);
CREATE TRIGGER trg_shift_allocation_updated_at BEFORE UPDATE ON attend.shift_allocation
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- ---------------------------------------------------------------------------------------------------------
-- Holidays (R4). Only PUBLIC holidays affect attendance. location_id NULL = all locations.
CREATE TABLE attend.holiday (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code         text NOT NULL UNIQUE CHECK (code ~ '^HOL-\d{4}-\d{2,}$'),
  holiday_date date NOT NULL,
  name         text NOT NULL CHECK (length(name) BETWEEN 2 AND 100),
  type         text NOT NULL CHECK (type IN ('PUBLIC', 'OPTIONAL')),
  location_id  bigint,
  source       text NOT NULL DEFAULT 'LOCAL' CHECK (source IN ('LOCAL', 'CSV_IMPORT', 'HRMS')),
  is_sample    boolean NOT NULL DEFAULT false,
  created_by   uuid,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  version      integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_holiday_location_id FOREIGN KEY (location_id) REFERENCES attend.location (id),
  CONSTRAINT fk_holiday_created_by FOREIGN KEY (created_by) REFERENCES attend.employee (id)
);
CREATE UNIQUE INDEX uq_holiday_date_location ON attend.holiday (holiday_date, coalesce(location_id, 0));
CREATE INDEX ix_holiday_location_id ON attend.holiday (location_id);
CREATE INDEX ix_holiday_created_by ON attend.holiday (created_by);
CREATE TRIGGER trg_holiday_updated_at BEFORE UPDATE ON attend.holiday
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

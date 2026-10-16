-- V004__employment.sql
-- Purpose: employment data per employee, managed locally until KRZ HRMS provides it (PENDING P-08).
-- Tables: employment.
-- The Employee ID pattern (default ^EMP-\d{4,6}$) is a setting enforced by the application; the database
-- guarantees only uniqueness. A synced employee has no row until employment details are first saved
-- ("Employment details missing").

CREATE TABLE attend.employment (
  employee_id      uuid PRIMARY KEY,
  hrms_employee_id text UNIQUE CHECK (length(hrms_employee_id) BETWEEN 1 AND 32),
  department_id    bigint,
  designation      text CHECK (length(designation) <= 120),
  employment_type  text CHECK (employment_type IN ('EMPLOYEE', 'CONTRACTOR', 'INTERN')),
  location_id      bigint,
  manager_id       uuid,
  join_date        date,
  end_date         date,
  tracking_mode    text NOT NULL DEFAULT 'NOT_TRACKED' CHECK (tracking_mode IN ('TRACKED', 'NOT_TRACKED')),
  tracking_reason  text DEFAULT 'NO_FACE' CHECK (tracking_reason IN ('NO_FACE', 'NO_CAMERA', 'EXEMPT')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  version          integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_employment_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_employment_department_id FOREIGN KEY (department_id) REFERENCES attend.department (id),
  CONSTRAINT fk_employment_location_id FOREIGN KEY (location_id) REFERENCES attend.location (id),
  CONSTRAINT fk_employment_manager_id FOREIGN KEY (manager_id) REFERENCES attend.employee (id),
  CONSTRAINT ck_employment_dates CHECK (end_date >= join_date),
  CONSTRAINT ck_employment_not_own_manager CHECK (manager_id <> employee_id),
  CONSTRAINT ck_employment_tracking CHECK ((tracking_mode = 'TRACKED') = (tracking_reason IS NULL))
);
CREATE INDEX ix_employment_department_id ON attend.employment (department_id);
CREATE INDEX ix_employment_location_id ON attend.employment (location_id);
CREATE INDEX ix_employment_manager_id ON attend.employment (manager_id);
CREATE TRIGGER trg_employment_updated_at BEFORE UPDATE ON attend.employment
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- R__900_dev_organisation.sql
-- Purpose: the prototype's SAMPLE organisation so the screens look real in development. Loaded ONLY by the
-- backend "dev" profile (Flyway location classpath:db/seed/dev); never in production.
-- Marked as sample: holidays have is_sample = true, department descriptions start with "Sample".
-- No employees (they come from Entra sync) and no cameras (the backend dev bootstrap creates CAM-01, because the
-- camera password must be encrypted). Idempotent: existing rows are left as they are.

-- Locations and the entry point
INSERT INTO attend.location (code, name, address) VALUES
  ('VLR', 'Vellore HQ', 'Katpadi Road, Vellore, Tamil Nadu'),
  ('CHN', 'Chennai office', 'OMR, Chennai, Tamil Nadu')
ON CONFLICT (code) DO NOTHING;

INSERT INTO attend.entry_point (code, location_id, name)
SELECT attend.next_code('EP-', 'attend.seq_entry_point_code', 2), l.id, 'Main entrance'
  FROM attend.location AS l
 WHERE l.code = 'VLR'
   AND NOT EXISTS (SELECT 1 FROM attend.entry_point AS ep WHERE ep.location_id = l.id AND ep.name = 'Main entrance');

-- Departments (prototype DEPARTMENT_SEED; heads are assigned once employees exist)
INSERT INTO attend.department (code, name, description) VALUES
  ('MGT', 'Management',      'Sample — Leadership team.'),
  ('ENG', 'Engineering',     'Sample — Product engineering, QA and DevOps.'),
  ('DES', 'Design',          'Sample — Product, UI and brand design.'),
  ('SAL', 'Sales',           'Sample — Sales teams in Vellore and Chennai.'),
  ('MKT', 'Marketing',       'Sample — Content, brand and digital marketing.'),
  ('HR',  'Human Resources', 'Sample — People operations, hiring and HR administration.'),
  ('FIN', 'Finance',         'Sample — Accounts, payroll and financial planning.'),
  ('SUP', 'Support',         'Sample — Customer support, including early and night shifts.'),
  ('IT',  'IT',              'Sample — IT infrastructure and support. Owns KRZ Attend.'),
  ('ADM', 'Administration',  'Sample — Front office, security and facilities.')
ON CONFLICT (code) DO NOTHING;

-- Shifts with the prototype's values (GEN, the organisation default, comes from the reference seed)
WITH created AS (
  INSERT INTO attend.shift (code, name, colour) VALUES
    ('SUP-E', 'Support Early',  '#0d9488'),
    ('ADM',   'Administration', '#b45309'),
    ('NGT',   'Night Support',  '#7c3aed')
  ON CONFLICT (code) DO NOTHING
  RETURNING id, code
)
INSERT INTO attend.shift_version (shift_id, effective_from, start_time, end_time, break_minutes, weekly_offs,
                                  late_grace_min, early_grace_min, half_day_below_hours, absent_below_hours,
                                  window_before_hours, day_close_hours, note, edited_by_label)
SELECT created.id, DATE '2026-01-01', v.start_time, v.end_time, v.break_minutes, v.weekly_offs,
       10, 10, 4.5, 2, 3, 4, 'Created', 'System'
  FROM created
  JOIN (VALUES ('SUP-E', TIME '07:00', TIME '16:00', 60, ARRAY[0]::smallint[]),
               ('ADM',   TIME '08:00', TIME '17:00', 60, ARRAY[0]::smallint[]),
               ('NGT',   TIME '21:00', TIME '06:00', 45, ARRAY[0, 6]::smallint[]))
       AS v(code, start_time, end_time, break_minutes, weekly_offs) ON v.code = created.code;

-- Department allocations from the prototype (Support -> SUP-E from 1 Sep 2026, Administration -> ADM from 1 Apr 2026)
INSERT INTO attend.shift_allocation (code, shift_id, target_type, department_id, valid_from, note, created_by_label)
SELECT attend.next_code('ALC-', 'attend.seq_allocation_code', 4), s.id, 'DEPARTMENT', d.id, a.valid_from, a.note, 'System'
  FROM (VALUES ('SUP', 'SUP-E', DATE '2026-09-01', 'Sample — Support moves to the early shift'),
               ('ADM', 'ADM',   DATE '2026-04-01', 'Sample — Front office opens at 8 AM'))
       AS a(department_code, shift_code, valid_from, note)
  JOIN attend.department AS d ON d.code = a.department_code
  JOIN attend.shift AS s ON s.code = a.shift_code
 WHERE NOT EXISTS (SELECT 1 FROM attend.shift_allocation AS x WHERE x.department_id = d.id);

-- The prototype's sample holiday list for the current IST year (verify against the company calendar: PENDING P-13)
-- Codes are numbered in date order: the ordered subquery feeds next_code row by row.
INSERT INTO attend.holiday (code, holiday_date, name, type, location_id, is_sample)
SELECT attend.next_code('HOL-' || h.year || '-', 'attend.seq_holiday_code', 2), h.holiday_date, h.name, h.type, h.location_id, true
  FROM (SELECT y.year, make_date(y.year, x.month, x.day) AS holiday_date, x.name, x.type, l.id AS location_id
          FROM (SELECT extract(year FROM (now() AT TIME ZONE 'Asia/Kolkata'))::int AS year) AS y
         CROSS JOIN (VALUES
                 (1, 1, 'New Year’s Day', 'PUBLIC', NULL),
                 (1, 15, 'Pongal', 'PUBLIC', NULL),
                 (1, 26, 'Republic Day', 'PUBLIC', NULL),
                 (3, 17, 'Chennai office anniversary', 'PUBLIC', 'CHN'),
                 (4, 14, 'Tamil New Year', 'PUBLIC', NULL),
                 (5, 1, 'May Day', 'PUBLIC', NULL),
                 (8, 15, 'Independence Day', 'PUBLIC', NULL),
                 (10, 2, 'Gandhi Jayanti', 'PUBLIC', NULL),
                 (12, 25, 'Christmas', 'PUBLIC', NULL),
                 (12, 31, 'New Year’s Eve (optional)', 'OPTIONAL', NULL))
               AS x(month, day, name, type, location_code)
          LEFT JOIN attend.location AS l ON l.code = x.location_code
         WHERE NOT EXISTS (SELECT 1 FROM attend.holiday AS e
                            WHERE e.holiday_date = make_date(y.year, x.month, x.day)
                              AND coalesce(e.location_id, 0) = coalesce(l.id, 0))
         ORDER BY holiday_date) AS h;

-- R__070_default_shift.sql
-- Purpose: the organisation default shift GEN "General", required by R1 (exactly one default shift always exists).
-- Created once; administrators may edit it, version it or make another shift the default afterwards.

WITH created AS (
  INSERT INTO attend.shift (code, name, colour, is_default)
  SELECT 'GEN', 'General', '#2563eb', NOT EXISTS (SELECT 1 FROM attend.shift WHERE is_default)
  ON CONFLICT (code) DO NOTHING
  RETURNING id
)
INSERT INTO attend.shift_version (shift_id, effective_from, start_time, end_time, break_minutes, weekly_offs,
                                  late_grace_min, early_grace_min, half_day_below_hours, absent_below_hours,
                                  window_before_hours, day_close_hours, note, edited_by_label)
SELECT created.id, DATE '2026-01-01', TIME '09:30', TIME '18:30', 60, ARRAY[0, 6]::smallint[],
       15, 15, 4.5, 2, 3, 4, 'Created', 'System'
  FROM created;

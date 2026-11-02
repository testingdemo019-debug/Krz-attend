-- V017__reid_model_threshold.sql
-- Purpose: the back-view Re-ID model changed to NVIDIA ReIdentificationNet (D-124). Its scores run higher than OSNet's,
-- so OSNet's reidThreshold would pass too many people. A threshold profile is immutable: the active one is copied to a
-- new profile with reidThreshold 0.75, which becomes active; the old one stays in the history. A new database has no
-- profile yet and gets 0.75 from the TP-1 seed.
-- Tables: threshold_profile (one new row).

DO $$
DECLARE
  current_values jsonb;
BEGIN
  SELECT profile_values INTO current_values FROM attend.threshold_profile WHERE active;
  IF FOUND THEN
    UPDATE attend.threshold_profile SET active = false WHERE active;
    INSERT INTO attend.threshold_profile (code, profile_values, note, created_by_label, active)
    VALUES (attend.next_code('TP-', 'attend.seq_threshold_profile_code', 1),
            jsonb_set(current_values, '{reidThreshold}', '0.75'::jsonb),
            'Back-view Re-ID model changed to NVIDIA ReIdentificationNet: threshold 0.75 for its scores',
            'System',
            true);
  END IF;
END
$$;

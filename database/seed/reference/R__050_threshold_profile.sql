-- R__050_threshold_profile.sql
-- Purpose: TP-1, the initial recognition thresholds for SFace (uncalibrated; PENDING P-11). Created only when no
-- profile exists, so calibrated profiles are never touched.

INSERT INTO attend.threshold_profile (code, profile_values, note, created_by_label, active)
SELECT attend.next_code('TP-', 'attend.seq_threshold_profile_code', 1),
       '{
          "accept": 0.42,
          "reviewBand": 0.07,
          "margin": 0.06,
          "agreeK": 3,
          "agreeN": 5,
          "minEyePx": 35,
          "maxYaw": 30,
          "sharpMin": 40,
          "qualityMin": 50,
          "recheckGrow": 20,
          "reidOn": true,
          "reidThreshold": 0.70
        }'::jsonb,
       'Initial defaults for SFace (uncalibrated)',
       'System',
       true
 WHERE NOT EXISTS (SELECT 1 FROM attend.threshold_profile);

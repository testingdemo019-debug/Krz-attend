-- R__030_settings_defaults.sql
-- Purpose: initial values for each settings area. Administrators edit them later, so a re-run never overwrites a
-- row (ON CONFLICT DO NOTHING). Values that come from deploy/.env (organisation name, time zone) are NOT stored
-- here: the backend's application.yml defaults supply them until an administrator saves Settings → General.

INSERT INTO attend.app_setting (key, value) VALUES
  ('general', '{
     "dateFormat": "D MMM YYYY",
     "timeFormat": "12h",
     "employeeIdPattern": "^EMP-\\d{4,6}$",
     "undoWindowSeconds": 10
   }'::jsonb),
  ('signin', '{
     "idleTimeoutMin": 30,
     "syncEvery": 6,
     "eraseAfterDays": 30
   }'::jsonb),
  ('attendanceRules', '{
     "duplicateMinutes": 2,
     "turnedBackSeconds": 60,
     "absentCutoffHours": 4,
     "workedHours": "SPAN",
     "pushMode": "FINAL"
   }'::jsonb),
  ('enrolment', '{
     "minGood": 3,
     "minQuality": 70,
     "minEyePx": 90,
     "livenessRequired": true,
     "allowUpload": true,
     "dupThreshold": 0.40,
     "lockFailures": 5,
     "lockWindowMin": 3,
     "lockMinutes": 30
   }'::jsonb),
  ('privacy', '{
     "cropDays": 30,
     "unknownHours": 24,
     "eventDays": 365,
     "auditDays": 365
   }'::jsonb),
  ('hrms', '{
     "maxAttempts": 5,
     "backoffSeconds": [5, 15, 30, 60, 120]
   }'::jsonb)
ON CONFLICT (key) DO NOTHING;

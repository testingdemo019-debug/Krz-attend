-- R__040_notification_rules.sql
-- Purpose: notification rules NR-01 … NR-11 with their default recipient roles. Administrators edit rules and
-- recipients in Settings → Notifications, so existing rules are never overwritten and recipients are inserted
-- only together with a newly created rule. Push stays false (PENDING P-06).

WITH created AS (
  INSERT INTO attend.notification_rule (code, label, description, severity, threshold, threshold_unit, params, in_app, email)
  VALUES
    ('NR-01', 'Camera offline for more than', 'A camera has sent no frames for longer than the threshold.', 'CRITICAL', 5, 'MINUTES', '{}', true, true),
    ('NR-02', 'Liveness alert (possible spoof)', 'A crossing or an enrolment capture failed the liveness check, or enrolment was locked after repeated failures.', 'WARN', NULL, NULL, '{}', true, true),
    ('NR-03', 'Review item waiting longer than', 'An open Review Queue item has waited longer than the threshold (once per item).', 'INFO', 30, 'MINUTES', '{}', true, false),
    ('NR-04', 'KRZ HRMS push failure', 'A punch could not be sent to KRZ HRMS after the last retry.', 'WARN', NULL, NULL, '{}', true, true),
    ('NR-05', 'Unknown face after office hours', 'An unknown face came in outside office hours (IST).', 'WARN', NULL, NULL, '{"officeStart": "08:00", "officeEnd": "20:00"}', true, true),
    ('NR-06', 'Access request received', 'Someone without a role asked for access to KRZ Attend.', 'INFO', NULL, NULL, '{}', true, true),
    ('NR-07', 'Employee missing an HRMS ID', 'A punch is blocked because the employee has no Employee ID.', 'INFO', NULL, NULL, '{}', true, false),
    ('NR-08', 'Day close finished with missing check-outs', 'Day close finalised a shift day with people still inside.', 'INFO', NULL, NULL, '{}', true, false),
    ('NR-09', 'Microsoft sign-in secret expires within', 'The Entra client secret expires within the threshold.', 'WARN', 30, 'DAYS', '{}', true, true),
    ('NR-10', 'Recognition worker not responding for more than', 'The recognition worker has sent no heartbeat for longer than the threshold.', 'CRITICAL', 2, 'MINUTES', '{}', true, true),
    ('NR-11', 'Directory sync failed', 'A Microsoft Entra directory sync run failed.', 'WARN', NULL, NULL, '{}', true, true)
  ON CONFLICT (code) DO NOTHING
  RETURNING code
)
INSERT INTO attend.notification_rule_role (rule_code, role_id)
SELECT created.code, r.id
  FROM created
  JOIN attend.role AS r
    ON r.code = 'ROLE-SYSADMIN'
    OR (r.code = 'ROLE-ENROLLER' AND created.code IN ('NR-02', 'NR-03'));

-- R__010_permissions.sql
-- Purpose: the 19 permission keys (vocabulary). Rows are owned by code, so labels and descriptions are updated
-- on every change of this file. The backend Permission enum must list exactly these keys.

INSERT INTO attend.permission (key, group_name, label, description, sort_order) VALUES
  ('live.view',             'Monitoring', 'View live camera',              'Open Live View and the camera tile.', 10),
  ('events.view',           'Monitoring', 'View crossings',                'Events log and crossing details.', 20),
  ('events.export',         'Monitoring', 'Export data',                   'Download lists as CSV.', 30),
  ('review.resolve',        'Monitoring', 'Resolve review items',          'Confirm, assign, mark as not an employee or reject items in the Review Queue.', 40),
  ('people.view',           'Employees',  'View employees',                'Employee list and profiles.', 50),
  ('people.view.sensitive', 'Employees',  'View sensitive data',           'Unblurred face crops, consent records and work email addresses.', 60),
  ('people.enrol',          'Employees',  'Enrol faces',                   'Run the enrolment wizard and record consent.', 70),
  ('people.deactivate',     'Employees',  'Deactivate face profiles',      'Stop matching an employee without erasing their data.', 80),
  ('people.erase',          'Employees',  'Erase biometric data',          'Permanently delete face templates and images.', 90),
  ('attendance.view.all',   'Attendance', 'View attendance',               'Today, Attendance log and every employee’s crossings.', 100),
  ('attendance.edit',       'Attendance', 'Correct punches',               'Void a punch (wrong person) or flip its direction (wrong IN/OUT). Nothing else.', 110),
  ('reports.view',          'Attendance', 'View reports',                  'Enrolment coverage and recognition quality.', 120),
  ('settings.cameras',      'Settings',   'Manage cameras',                'Camera connections, direction mode, tripwire and calibration.', 130),
  ('settings.recognition',  'Settings',   'Manage recognition',            'Thresholds, threshold profiles and enrolment quality. Also shows numeric similarity scores.', 140),
  ('settings.attendance',   'Settings',   'Manage organisation and rules', 'Locations, departments, shifts, shift allocation, holidays, employment fields, punch rules, notifications.', 150),
  ('settings.integrations', 'Settings',   'Manage integrations',           'Microsoft sign-in, KRZ HRMS, system.', 160),
  ('settings.roles',        'Settings',   'Manage users and roles',        'Create roles, give users access, handle access requests.', 170),
  ('settings.privacy',      'Settings',   'Manage privacy',                'Retention, consent notices, privacy requests.', 180),
  ('audit.view',            'Settings',   'View audit log',                'Read-only log of every change.', 190)
ON CONFLICT (key) DO UPDATE
   SET group_name = EXCLUDED.group_name,
       label = EXCLUDED.label,
       description = EXCLUDED.description,
       sort_order = EXCLUDED.sort_order;

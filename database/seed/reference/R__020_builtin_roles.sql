-- R__020_builtin_roles.sql
-- Purpose: the two built-in roles. System Administrator is locked and always holds every permission (a key added
-- later is granted here automatically; the backend also treats it as all-permissions). Enroller is editable, so
-- its default permissions are inserted only when the role itself is created — a re-run never re-adds a
-- permission an administrator removed.

INSERT INTO attend.role (code, name, description, built_in, locked, created_by_label)
VALUES ('ROLE-SYSADMIN', 'System Administrator',
        'Full access to KRZ Attend, including users and roles, cameras, organisation data and integrations.',
        true, true, 'System')
ON CONFLICT (code) DO NOTHING;

INSERT INTO attend.role_permission (role_id, permission_key)
SELECT r.id, p.key
  FROM attend.role AS r
 CROSS JOIN attend.permission AS p
 WHERE r.code = 'ROLE-SYSADMIN'
ON CONFLICT DO NOTHING;

WITH created AS (
  INSERT INTO attend.role (code, name, description, built_in, locked, created_by_label)
  VALUES ('ROLE-ENROLLER', 'Enroller', 'Enrols faces and records consent at reception; resolves review items.',
          true, false, 'System')
  ON CONFLICT (code) DO NOTHING
  RETURNING id
)
INSERT INTO attend.role_permission (role_id, permission_key)
SELECT created.id, k.key
  FROM created
 CROSS JOIN (VALUES ('live.view'), ('review.resolve'), ('people.view'), ('people.enrol')) AS k(key);

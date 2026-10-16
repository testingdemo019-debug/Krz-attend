-- V012__worker_views.sql
-- Purpose: the ONLY database surface of the Python worker (role krz_worker, read-only). Every view is owned by
-- krz_owner, is a security barrier, and is granted to krz_worker individually. krz_worker has no other grants.
-- Views: v_worker_camera, v_worker_gallery, v_worker_recognition, v_worker_employee_label, v_worker_config_version.

-- Camera connection and geometry. The password stays AES-256-GCM encrypted; the worker decrypts it in memory.
CREATE VIEW attend.v_worker_camera WITH (security_barrier) AS
SELECT c.code, c.name, c.enabled, c.direction_mode, c.host, c.rtsp_port, c.onvif_port, c.main_path,
       c.username, c.password_enc, c.zone, c.wire, c.confirm_frames, c.ignore_uturn_sec, c.liveness_enabled,
       c.config_version
  FROM attend.camera AS c;

-- Face templates that may be matched: active template, profile ENROLLED or NEEDS_REENROL, account enabled and the
-- current consent GIVEN. Embeddings stay encrypted; the worker refuses templates of another model_name/dim.
CREATE VIEW attend.v_worker_gallery WITH (security_barrier) AS
SELECT t.employee_id, t.id AS template_id, t.embedding_enc, t.key_id, t.model_name, t.dim
  FROM attend.face_template AS t
  JOIN attend.face_profile AS fp ON fp.employee_id = t.employee_id
  JOIN attend.employee AS e ON e.id = t.employee_id
  JOIN attend.consent AS c ON c.employee_id = t.employee_id AND c.is_current
 WHERE t.active
   AND fp.status IN ('ENROLLED', 'NEEDS_REENROL')
   AND e.account_enabled
   AND c.status = 'GIVEN';

-- The active threshold profile as typed columns (R14), so a missing or mistyped value fails loudly.
CREATE VIEW attend.v_worker_recognition WITH (security_barrier) AS
SELECT tp.code,
       (tp.profile_values ->> 'accept')::numeric         AS accept,
       (tp.profile_values ->> 'reviewBand')::numeric     AS review_band,
       (tp.profile_values ->> 'margin')::numeric         AS margin,
       (tp.profile_values ->> 'agreeK')::integer         AS agree_k,
       (tp.profile_values ->> 'agreeN')::integer         AS agree_n,
       (tp.profile_values ->> 'minEyePx')::integer       AS min_eye_px,
       (tp.profile_values ->> 'maxYaw')::integer         AS max_yaw,
       (tp.profile_values ->> 'sharpMin')::integer       AS sharp_min,
       (tp.profile_values ->> 'qualityMin')::integer     AS quality_min,
       (tp.profile_values ->> 'recheckGrow')::integer    AS recheck_grow,
       (tp.profile_values ->> 'reidOn')::boolean         AS reid_on,
       (tp.profile_values ->> 'reidThreshold')::numeric  AS reid_threshold
  FROM attend.threshold_profile AS tp
 WHERE tp.active;

-- Display names for overlay labels: enabled employees with an active face profile.
CREATE VIEW attend.v_worker_employee_label WITH (security_barrier) AS
SELECT e.id AS employee_id, e.display_name
  FROM attend.employee AS e
  JOIN attend.face_profile AS fp ON fp.employee_id = e.id
 WHERE e.account_enabled
   AND fp.status IN ('ENROLLED', 'NEEDS_REENROL');

-- One row the worker polls every 30 s; any change means "reload that part".
-- camera_config is a fingerprint of every camera's config_version, so adding or removing a camera changes it too
-- (a plain max(config_version) would miss a new camera that starts at 1).
CREATE VIEW attend.v_worker_config_version WITH (security_barrier) AS
SELECT s.gallery_version,
       (SELECT md5(coalesce(string_agg(c.code || ':' || c.config_version, ',' ORDER BY c.code), ''))
          FROM attend.camera AS c) AS camera_config,
       (SELECT tp.code FROM attend.threshold_profile AS tp WHERE tp.active) AS recognition_profile
  FROM attend.worker_sync_state AS s
 WHERE s.id = 1;

-- The default privileges for krz_owner also cover views; the backend does not need these.
REVOKE ALL ON attend.v_worker_camera, attend.v_worker_gallery, attend.v_worker_recognition,
              attend.v_worker_employee_label, attend.v_worker_config_version FROM krz_app;

GRANT SELECT ON attend.v_worker_camera TO krz_worker;
GRANT SELECT ON attend.v_worker_gallery TO krz_worker;
GRANT SELECT ON attend.v_worker_recognition TO krz_worker;
GRANT SELECT ON attend.v_worker_employee_label TO krz_worker;
GRANT SELECT ON attend.v_worker_config_version TO krz_worker;

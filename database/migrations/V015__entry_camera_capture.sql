-- V015__entry_camera_capture.sql
-- Purpose: face templates from the attendance camera itself (D-118).
--   * "Capture at entry point" (enrolment method CAMERA): the worker collects the enrolled person's best face per
--     angle group while they walk through an entry point; the session records which camera it used.
--   * A template from an identified crossing's face crop (source CROSSING), as review already does (source REVIEW).
--   * The light a camera-made template was taken in (day, evening, night infrared), for the profile's coverage.
--   * Crossings of the person being captured are recorded but never counted (crossing.enrolment_capture).
-- Tables: enrolment_session (method, camera_id), enrolment_capture (light), face_template (source, crossing_id,
--         light), crossing (enrolment_capture), face_profile_history (action TEMPLATE_ADDED_FROM_CROSSING).

ALTER TABLE attend.enrolment_session DROP CONSTRAINT enrolment_session_method_check;
ALTER TABLE attend.enrolment_session
  ADD CONSTRAINT enrolment_session_method_check CHECK (method IN ('LIVE', 'UPLOAD', 'CAMERA')),
  ADD COLUMN camera_id bigint,
  ADD CONSTRAINT fk_enrolment_session_camera_id FOREIGN KEY (camera_id) REFERENCES attend.camera (id),
  ADD CONSTRAINT ck_enrolment_session_camera CHECK ((method = 'CAMERA') = (camera_id IS NOT NULL));
CREATE INDEX ix_enrolment_session_camera_id ON attend.enrolment_session (camera_id);

ALTER TABLE attend.enrolment_capture
  ADD COLUMN light text CHECK (light IN ('DAY', 'EVENING', 'NIGHT_IR'));

ALTER TABLE attend.face_template DROP CONSTRAINT face_template_source_check;
ALTER TABLE attend.face_template
  ADD CONSTRAINT face_template_source_check CHECK (source IN ('LIVE', 'UPLOAD', 'REVIEW', 'CAMERA', 'CROSSING')),
  ADD COLUMN crossing_id bigint,
  ADD COLUMN light text CHECK (light IN ('DAY', 'EVENING', 'NIGHT_IR')),
  ADD CONSTRAINT fk_face_template_crossing_id FOREIGN KEY (crossing_id) REFERENCES attend.crossing (id) ON DELETE SET NULL,
  ADD CONSTRAINT ck_face_template_crossing CHECK (source = 'CROSSING' OR crossing_id IS NULL);
CREATE INDEX ix_face_template_crossing_id ON attend.face_template (crossing_id);

ALTER TABLE attend.crossing
  ADD COLUMN enrolment_capture boolean NOT NULL DEFAULT false;

ALTER TABLE attend.face_profile_history DROP CONSTRAINT face_profile_history_action_check;
ALTER TABLE attend.face_profile_history
  ADD CONSTRAINT face_profile_history_action_check CHECK (action IN (
    'ENROLLED', 'REENROLLED', 'FLAGGED_REENROL', 'DEACTIVATED', 'REACTIVATED', 'ERASED', 'TEMPLATE_ADDED_FROM_REVIEW',
    'TEMPLATE_ADDED_FROM_CROSSING', 'ENROLMENT_DECLINED', 'CONSENT_WITHDRAWN'));

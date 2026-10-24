-- V016__doorway_zone.sql
-- Purpose: a second way a camera counts crossings, for compact entrances (D-120).
--   * counting_method: LINE_CROSSING (the floor tripwire, as before) or DOORWAY_ZONE (a walk counts by where it starts
--     and ends: from the door into the room is IN, from the room out through the door is OUT).
--   * door_zone: the polygon around the door opening a DOORWAY_ZONE camera needs; the tripwire, confirmation frames and
--     U-turn window are kept but not used by that method, so switching back restores them.
--   * crossing.direction_unsure: a doorway walk whose direction no check supported; counted, and shown in Events so it
--     can be checked and flipped.
-- The worker reads the new camera columns (and faces_visible_when, which its side check needs) through v_worker_camera.
-- Tables: camera (counting_method, door_zone), crossing (direction_unsure); view v_worker_camera.

ALTER TABLE attend.camera
  ADD COLUMN counting_method text NOT NULL DEFAULT 'LINE_CROSSING'
    CHECK (counting_method IN ('LINE_CROSSING', 'DOORWAY_ZONE')),
  ADD COLUMN door_zone jsonb,
  ADD CONSTRAINT ck_camera_door_zone CHECK (counting_method = 'LINE_CROSSING' OR door_zone IS NOT NULL);

ALTER TABLE attend.crossing
  ADD COLUMN direction_unsure boolean NOT NULL DEFAULT false;

-- New columns go last: CREATE OR REPLACE VIEW keeps the existing ones, the owner and the krz_worker grant.
CREATE OR REPLACE VIEW attend.v_worker_camera WITH (security_barrier) AS
SELECT c.code, c.name, c.enabled, c.direction_mode, c.host, c.rtsp_port, c.onvif_port, c.main_path,
       c.username, c.password_enc, c.zone, c.wire, c.confirm_frames, c.ignore_uturn_sec, c.liveness_enabled,
       c.config_version, c.counting_method, c.door_zone, c.faces_visible_when
  FROM attend.camera AS c;

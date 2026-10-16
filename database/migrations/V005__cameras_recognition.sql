-- V005__cameras_recognition.sql
-- Purpose: cameras (connection, geometry, status), their health history, recognition threshold profiles and
-- calibration runs.
-- Tables: camera, camera_outage, camera_health_daily, threshold_profile, calibration_run.

-- ---------------------------------------------------------------------------------------------------------
-- camera — the password is AES-256-GCM encrypted by the backend and never returned by the API.
-- zone: normalised polygon [[x,y],…] (>= 3 points). wire: {"a":[x,y],"b":[x,y],"inSide":1|-1}.
-- config_version is bumped on every configuration change (the worker reloads that camera).
CREATE TABLE attend.camera (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                text NOT NULL UNIQUE CHECK (code ~ '^CAM-\d{2,}$'),
  name                text NOT NULL CHECK (length(name) BETWEEN 2 AND 80),
  entry_point_id      bigint NOT NULL,
  direction_mode      text NOT NULL DEFAULT 'BOTH' CHECK (direction_mode IN ('BOTH', 'IN', 'OUT', 'MONITOR')),
  mount_note          text CHECK (length(mount_note) <= 300),
  faces_visible_when  text NOT NULL DEFAULT 'ENTERING' CHECK (faces_visible_when IN ('ENTERING', 'LEAVING')),
  model_name          text CHECK (length(model_name) <= 120),
  host                text NOT NULL CHECK (length(host) BETWEEN 1 AND 253),
  rtsp_port           integer NOT NULL DEFAULT 554 CHECK (rtsp_port BETWEEN 1 AND 65535),
  onvif_port          integer NOT NULL DEFAULT 80 CHECK (onvif_port BETWEEN 1 AND 65535),
  main_path           text NOT NULL CHECK (main_path ~ '^/' AND length(main_path) <= 300),
  sub_path            text NOT NULL CHECK (sub_path ~ '^/' AND length(sub_path) <= 300),
  username            text CHECK (length(username) <= 120),
  password_enc        bytea,
  password_updated_at timestamptz,
  enabled             boolean NOT NULL DEFAULT true,
  liveness_enabled    boolean NOT NULL DEFAULT true,
  zone                jsonb CHECK (zone IS NULL OR (jsonb_typeof(zone) = 'array' AND jsonb_array_length(zone) >= 3)),
  wire                jsonb CHECK (wire IS NULL OR (jsonb_typeof(wire) = 'object'
                                    AND jsonb_typeof(wire -> 'a') = 'array'
                                    AND jsonb_typeof(wire -> 'b') = 'array'
                                    AND (wire ->> 'inSide') IN ('1', '-1'))),
  confirm_frames      smallint NOT NULL DEFAULT 3 CHECK (confirm_frames BETWEEN 1 AND 30),
  ignore_uturn_sec    smallint NOT NULL DEFAULT 5 CHECK (ignore_uturn_sec BETWEEN 0 AND 60),
  status              text NOT NULL DEFAULT 'CONNECTING' CHECK (status IN ('LIVE', 'CONNECTING', 'OFFLINE', 'DISABLED')),
  status_since        timestamptz,
  last_seen_at        timestamptz,
  fps                 numeric(5,2),
  latency_ms          integer CHECK (latency_ms >= 0),
  resolution          text CHECK (length(resolution) <= 20),
  codec               text CHECK (length(codec) <= 40),
  clock_offset_s      numeric(8,2),
  config_version      bigint NOT NULL DEFAULT 1,
  created_by          uuid,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  version             integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_camera_entry_point_id FOREIGN KEY (entry_point_id) REFERENCES attend.entry_point (id),
  CONSTRAINT fk_camera_created_by FOREIGN KEY (created_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_camera_password_pair CHECK ((password_enc IS NULL) = (password_updated_at IS NULL))
);
CREATE INDEX ix_camera_entry_point_id ON attend.camera (entry_point_id);
CREATE INDEX ix_camera_created_by ON attend.camera (created_by);
CREATE TRIGGER trg_camera_updated_at BEFORE UPDATE ON attend.camera
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- camera_outage — one open row (ended_at NULL) per camera while it is offline.
CREATE TABLE attend.camera_outage (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  camera_id  bigint NOT NULL,
  started_at timestamptz NOT NULL,
  ended_at   timestamptz,
  reason     text CHECK (length(reason) <= 200),
  CONSTRAINT fk_camera_outage_camera_id FOREIGN KEY (camera_id) REFERENCES attend.camera (id),
  CONSTRAINT ck_camera_outage_range CHECK (ended_at >= started_at)
);
CREATE INDEX ix_camera_outage_camera_id ON attend.camera_outage (camera_id, started_at DESC);
CREATE UNIQUE INDEX uq_camera_outage_open ON attend.camera_outage (camera_id) WHERE ended_at IS NULL;

CREATE TABLE attend.camera_health_daily (
  camera_id       bigint NOT NULL,
  day             date NOT NULL,
  uptime_pct      numeric(5,2) NOT NULL CHECK (uptime_pct BETWEEN 0 AND 100),
  reconnects      integer NOT NULL DEFAULT 0 CHECK (reconnects >= 0),
  offline_minutes integer NOT NULL DEFAULT 0 CHECK (offline_minutes BETWEEN 0 AND 1440),
  PRIMARY KEY (camera_id, day),
  CONSTRAINT fk_camera_health_daily_camera_id FOREIGN KEY (camera_id) REFERENCES attend.camera (id)
);

-- ---------------------------------------------------------------------------------------------------------
-- threshold_profile — immutable recognition thresholds (R14). Saving creates a new TP-n and activates it.
-- The column is "profile_values" because VALUES is a reserved word in PostgreSQL (DECISIONS).
CREATE TABLE attend.threshold_profile (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code             text NOT NULL UNIQUE CHECK (code ~ '^TP-\d+$'),
  profile_values   jsonb NOT NULL CHECK (jsonb_typeof(profile_values) = 'object'),
  note             text CHECK (length(note) <= 300),
  created_by       uuid,
  created_by_label text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  active           boolean NOT NULL DEFAULT false,
  CONSTRAINT fk_threshold_profile_created_by FOREIGN KEY (created_by) REFERENCES attend.employee (id)
);
CREATE UNIQUE INDEX uq_threshold_profile_single_active ON attend.threshold_profile (active) WHERE active;
CREATE INDEX ix_threshold_profile_created_by ON attend.threshold_profile (created_by);

CREATE FUNCTION attend.threshold_profile_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.profile_values IS DISTINCT FROM OLD.profile_values OR NEW.code IS DISTINCT FROM OLD.code THEN
    RAISE EXCEPTION 'threshold_profile % is immutable; save a new profile instead', OLD.code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_threshold_profile_immutable BEFORE UPDATE ON attend.threshold_profile
  FOR EACH ROW EXECUTE FUNCTION attend.threshold_profile_immutable();

-- ---------------------------------------------------------------------------------------------------------
-- calibration_run — a "test walk": for its duration the worker reports crossings to the caller, not the stream.
CREATE TABLE attend.calibration_run (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  camera_id     bigint NOT NULL,
  worker_run_id text CHECK (length(worker_run_id) <= 64),
  started_by    uuid,
  started_at    timestamptz NOT NULL DEFAULT now(),
  finished_at   timestamptz,
  status        text NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
  result        jsonb,
  CONSTRAINT fk_calibration_run_camera_id FOREIGN KEY (camera_id) REFERENCES attend.camera (id),
  CONSTRAINT fk_calibration_run_started_by FOREIGN KEY (started_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_calibration_run_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);
CREATE INDEX ix_calibration_run_camera_id ON attend.calibration_run (camera_id, started_at DESC);
CREATE INDEX ix_calibration_run_started_by ON attend.calibration_run (started_by);

-- V007__crossings_review.sql
-- Purpose: the Review Queue, crossings (one row per tripwire crossing reported by the worker) and corrections.
-- Tables: review_item, crossing, correction. Adds the foreign key face_template.review_item_id.
-- Retention (R17) deletes in FK-safe order: hrms_outbox -> attendance_day -> correction -> crossing -> orphaned
-- review_item. Every foreign key into crossing is therefore ON DELETE RESTRICT.

-- ---------------------------------------------------------------------------------------------------------
-- review_item — RV-nnnn. Unknown faces are clustered per IST day ("Unknown #A1"); undo_snapshot holds what an
-- undo restores until undo_until.
CREATE TABLE attend.review_item (
  id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                   text NOT NULL UNIQUE CHECK (code ~ '^RV-\d{4,}$'),
  type                   text NOT NULL CHECK (type IN ('UNKNOWN', 'UNCERTAIN', 'REID', 'LIVENESS')),
  status                 text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'EXPIRED')),
  label                  text CHECK (length(label) <= 60),
  unknown_cluster        text CHECK (unknown_cluster ~ '^\d{4}-\d{2}-\d{2}:[A-Z]\d+$'),
  created_at             timestamptz NOT NULL DEFAULT now(),
  last_sighting_at       timestamptz NOT NULL,
  resolution_action      text CHECK (resolution_action IN ('CONFIRM', 'ASSIGN', 'NOT_EMPLOYEE', 'REJECT', 'SPOOF', 'FALSE_ALARM')),
  resolution_employee_id uuid,
  resolved_by            uuid,
  resolved_at            timestamptz,
  resolution_reason      text CHECK (length(resolution_reason) <= 500),
  resolution_note        text CHECK (length(resolution_note) <= 500),
  template_added_id      bigint,
  wait_notified_at       timestamptz,
  undo_snapshot          jsonb,
  undo_until             timestamptz,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  version                integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_review_item_resolution_employee_id FOREIGN KEY (resolution_employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_review_item_resolved_by FOREIGN KEY (resolved_by) REFERENCES attend.employee (id),
  CONSTRAINT fk_review_item_template_added_id FOREIGN KEY (template_added_id) REFERENCES attend.face_template (id) ON DELETE SET NULL,
  CONSTRAINT ck_review_item_resolved CHECK ((status = 'RESOLVED') = (resolution_action IS NOT NULL AND resolved_at IS NOT NULL)),
  CONSTRAINT ck_review_item_unknown_cluster CHECK (unknown_cluster IS NULL OR type = 'UNKNOWN')
);
CREATE INDEX ix_review_item_status_type ON attend.review_item (status, type);
CREATE INDEX ix_review_item_open_cluster ON attend.review_item (unknown_cluster) WHERE status = 'OPEN';
CREATE INDEX ix_review_item_resolution_employee_id ON attend.review_item (resolution_employee_id);
CREATE INDEX ix_review_item_resolved_by ON attend.review_item (resolved_by);
CREATE INDEX ix_review_item_template_added_id ON attend.review_item (template_added_id);
CREATE TRIGGER trg_review_item_updated_at BEFORE UPDATE ON attend.review_item
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

ALTER TABLE attend.face_template
  ADD CONSTRAINT fk_face_template_review_item_id FOREIGN KEY (review_item_id) REFERENCES attend.review_item (id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------------------------------------
-- crossing — EV-nnnnnn. crossing_uid (UUIDv7 from the worker) is the idempotency key of stream ingestion.
-- employee_id is set only for AUTO identities and review decisions: an uncertain candidate stays in candidates.
-- effect / effect_reason are materialised by the backend's punch rules engine.
CREATE TABLE attend.crossing (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                text NOT NULL UNIQUE CHECK (code ~ '^EV-\d{6,}$'),
  crossing_uid        uuid NOT NULL UNIQUE,
  camera_id           bigint NOT NULL,
  occurred_at         timestamptz NOT NULL,
  received_at         timestamptz NOT NULL DEFAULT now(),
  direction           text NOT NULL CHECK (direction IN ('IN', 'OUT')),
  original_direction  text CHECK (original_direction IN ('IN', 'OUT')),
  u_turn              boolean NOT NULL DEFAULT false,
  method              text CHECK (method IN ('FACE', 'REID', 'REVIEW')),
  employee_id         uuid,
  shift_day           date,
  decision            text NOT NULL CHECK (decision IN ('AUTO', 'PENDING_REVIEW', 'RESOLVED_IN_REVIEW', 'NOT_AN_EMPLOYEE',
                                                        'REJECTED', 'SPOOF_CONFIRMED', 'EXPIRED_NO_ACTION')),
  score               numeric(5,4),
  second_score        numeric(5,4),
  margin              numeric(5,4),
  agree_k             smallint CHECK (agree_k >= 0),
  agree_n             smallint CHECK (agree_n >= 0),
  reid_score          numeric(5,4),
  liveness            text CHECK (liveness IN ('PASSED', 'FAILED', 'SKIPPED')),
  liveness_score      numeric(5,4),
  quality             jsonb,
  candidates          jsonb CHECK (candidates IS NULL OR jsonb_typeof(candidates) = 'array'),
  face_crop_path      text CHECK (length(face_crop_path) <= 300),
  face_crop_blur_path text CHECK (length(face_crop_blur_path) <= 300),
  body_crop_path      text CHECK (length(body_crop_path) <= 300),
  body_crop_blur_path text CHECK (length(body_crop_blur_path) <= 300),
  crops_purged_at     timestamptz,
  unknown_label       text CHECK (length(unknown_label) <= 60),
  unknown_cluster     text CHECK (unknown_cluster ~ '^\d{4}-\d{2}-\d{2}:[A-Z]\d+$'),
  review_item_id      bigint,
  resolved_by         uuid,
  resolved_at         timestamptz,
  voided              boolean NOT NULL DEFAULT false,
  effect              text CHECK (effect IN ('CHECK_IN', 'CHECK_OUT', 'MOVEMENT', 'IGNORED_DUPLICATE', 'IGNORED_TURNED_BACK',
                                             'IGNORED_UNKNOWN', 'PENDING_REVIEW', 'VOIDED')),
  effect_reason       text CHECK (length(effect_reason) <= 500),
  model_version       text CHECK (length(model_version) <= 200),
  track_id            integer,
  worker_id           text CHECK (length(worker_id) <= 64),
  trace_id            text CHECK (length(trace_id) <= 64),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  version             integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_crossing_camera_id FOREIGN KEY (camera_id) REFERENCES attend.camera (id),
  CONSTRAINT fk_crossing_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_crossing_review_item_id FOREIGN KEY (review_item_id) REFERENCES attend.review_item (id) ON DELETE SET NULL,
  CONSTRAINT fk_crossing_resolved_by FOREIGN KEY (resolved_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_crossing_identity CHECK ((employee_id IS NULL) = (method IS NULL)),
  CONSTRAINT ck_crossing_flip CHECK (original_direction IS NULL OR original_direction <> direction)
);
CREATE INDEX ix_crossing_occurred_at ON attend.crossing (occurred_at DESC);
CREATE INDEX ix_crossing_employee_day ON attend.crossing (employee_id, shift_day);
CREATE INDEX ix_crossing_camera_occurred_at ON attend.crossing (camera_id, occurred_at DESC);
CREATE INDEX ix_crossing_pending_review ON attend.crossing (decision) WHERE decision = 'PENDING_REVIEW';
CREATE INDEX ix_crossing_review_item_id ON attend.crossing (review_item_id);
CREATE INDEX ix_crossing_resolved_by ON attend.crossing (resolved_by);
CREATE TRIGGER trg_crossing_updated_at BEFORE UPDATE ON attend.crossing
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- ---------------------------------------------------------------------------------------------------------
-- correction — the only two kinds (R11): VOID (wrong person) and FLIP (wrong direction). Undo sets undone_*.
CREATE TABLE attend.correction (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  crossing_id bigint NOT NULL,
  kind        text NOT NULL CHECK (kind IN ('VOID', 'FLIP')),
  reason      text NOT NULL CHECK (length(reason) BETWEEN 5 AND 500),
  actor_id    uuid NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  undone_at   timestamptz,
  undone_by   uuid,
  CONSTRAINT fk_correction_crossing_id FOREIGN KEY (crossing_id) REFERENCES attend.crossing (id) ON DELETE RESTRICT,
  CONSTRAINT fk_correction_actor_id FOREIGN KEY (actor_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_correction_undone_by FOREIGN KEY (undone_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_correction_undo CHECK ((undone_at IS NULL) = (undone_by IS NULL))
);
CREATE INDEX ix_correction_crossing_id ON attend.correction (crossing_id, created_at DESC);
CREATE INDEX ix_correction_actor_id ON attend.correction (actor_id);
CREATE INDEX ix_correction_undone_by ON attend.correction (undone_by);

-- V006__consent_enrolment.sql
-- Purpose: consent notices and consent records, face profiles and templates, enrolment sessions and captures,
-- liveness lockout; plus the gallery-version triggers the worker relies on.
-- Tables: consent_notice, consent, face_profile, face_profile_history, face_template, enrolment_session,
--         enrolment_capture, liveness_failure, enrolment_lockout.

-- ---------------------------------------------------------------------------------------------------------
-- consent_notice — publishing a new version deactivates the previous one (one active notice).
CREATE TABLE attend.consent_notice (
  id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  version            integer NOT NULL UNIQUE CHECK (version >= 1),
  text               text NOT NULL CHECK (length(text) BETWEEN 80 AND 10000),
  published_at       timestamptz NOT NULL DEFAULT now(),
  published_by       uuid,
  published_by_label text,
  active             boolean NOT NULL DEFAULT false,
  CONSTRAINT fk_consent_notice_published_by FOREIGN KEY (published_by) REFERENCES attend.employee (id)
);
CREATE UNIQUE INDEX uq_consent_notice_single_active ON attend.consent_notice (active) WHERE active;
CREATE INDEX ix_consent_notice_published_by ON attend.consent_notice (published_by);

-- consent — one current record per employee; older records (and a withdrawal) are kept as proof.
-- The consent document is stored encrypted under DATA_DIR with a random name.
CREATE TABLE attend.consent (
  id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  employee_id        uuid NOT NULL,
  status             text NOT NULL CHECK (status IN ('GIVEN', 'PENDING', 'NONE', 'DECLINED', 'WITHDRAWN')),
  method             text CHECK (method IN ('WRITTEN_FORM', 'EMAIL_CONFIRMATION', 'WRITTEN_REQUEST')),
  notice_version     integer,
  recorded_by        uuid,
  recorded_by_label  text,
  recorded_at        timestamptz NOT NULL DEFAULT now(),
  given_on           date,
  withdrawn_on       date,
  document_path      text CHECK (length(document_path) <= 300),
  document_mime      text CHECK (document_mime IN ('application/pdf', 'image/png', 'image/jpeg')),
  document_sha256    text CHECK (document_sha256 ~ '^[0-9a-f]{64}$'),
  is_current         boolean NOT NULL DEFAULT true,
  CONSTRAINT fk_consent_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_consent_notice_version FOREIGN KEY (notice_version) REFERENCES attend.consent_notice (version),
  CONSTRAINT fk_consent_recorded_by FOREIGN KEY (recorded_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_consent_given CHECK (status <> 'GIVEN' OR (given_on IS NOT NULL AND notice_version IS NOT NULL AND method IS NOT NULL)),
  CONSTRAINT ck_consent_withdrawn CHECK ((status = 'WITHDRAWN') = (withdrawn_on IS NOT NULL)),
  CONSTRAINT ck_consent_document CHECK ((document_path IS NULL) = (document_mime IS NULL)
                                       AND (document_path IS NULL) = (document_sha256 IS NULL))
);
CREATE UNIQUE INDEX uq_consent_current ON attend.consent (employee_id) WHERE is_current;
CREATE INDEX ix_consent_employee_id ON attend.consent (employee_id, recorded_at DESC);
CREATE INDEX ix_consent_notice_version ON attend.consent (notice_version);
CREATE INDEX ix_consent_recorded_by ON attend.consent (recorded_by);

-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE attend.face_profile (
  employee_id          uuid PRIMARY KEY,
  status               text NOT NULL DEFAULT 'NOT_ENROLLED'
                         CHECK (status IN ('ENROLLED', 'NOT_ENROLLED', 'NEEDS_REENROL', 'CONSENT_PENDING', 'DEACTIVATED', 'ERASED')),
  reason               text CHECK (length(reason) <= 300),
  scheduled_erasure_on date,
  enrolled_at          timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  version              integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_face_profile_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id)
);
CREATE INDEX ix_face_profile_scheduled_erasure ON attend.face_profile (scheduled_erasure_on) WHERE scheduled_erasure_on IS NOT NULL;
CREATE TRIGGER trg_face_profile_updated_at BEFORE UPDATE ON attend.face_profile
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- face_profile_history — the profile's "History" tab. The frontend vocabulary maps each action to its label.
CREATE TABLE attend.face_profile_history (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  employee_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  action      text NOT NULL CHECK (action IN ('ENROLLED', 'REENROLLED', 'FLAGGED_REENROL', 'DEACTIVATED', 'REACTIVATED',
                                              'ERASED', 'TEMPLATE_ADDED_FROM_REVIEW', 'ENROLMENT_DECLINED', 'CONSENT_WITHDRAWN')),
  actor_id    uuid,
  actor_label text NOT NULL,
  detail      text CHECK (length(detail) <= 500),
  CONSTRAINT fk_face_profile_history_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_face_profile_history_actor_id FOREIGN KEY (actor_id) REFERENCES attend.employee (id)
);
CREATE INDEX ix_face_profile_history_employee_id ON attend.face_profile_history (employee_id, occurred_at DESC);
CREATE INDEX ix_face_profile_history_actor_id ON attend.face_profile_history (actor_id);

-- face_template — encrypted embeddings (v1|keyId|nonce|ciphertext+tag). key_id lets key rotation find old rows.
-- review_item_id gets its foreign key in V007 (review_item does not exist yet).
CREATE TABLE attend.face_template (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  employee_id    uuid NOT NULL,
  embedding_enc  bytea NOT NULL,
  key_id         text NOT NULL CHECK (key_id ~ '^[A-Za-z0-9_-]{1,32}$'),
  model_name     text NOT NULL CHECK (length(model_name) <= 120),
  model_version  text NOT NULL CHECK (length(model_version) <= 120),
  dim            smallint NOT NULL CHECK (dim > 0),
  quality        smallint CHECK (quality BETWEEN 0 AND 100),
  eye_px         smallint CHECK (eye_px >= 0),
  pose           text CHECK (pose IN ('FRONT', 'TURN_LEFT', 'TURN_RIGHT', 'CHIN_DOWN')),
  source         text NOT NULL CHECK (source IN ('LIVE', 'UPLOAD', 'REVIEW')),
  thumbnail_path text CHECK (length(thumbnail_path) <= 300),
  review_item_id bigint,
  active         boolean NOT NULL DEFAULT true,
  deactivated_at timestamptz,
  created_by     uuid,
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_face_template_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_face_template_created_by FOREIGN KEY (created_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_face_template_active CHECK (active = (deactivated_at IS NULL))
);
CREATE INDEX ix_face_template_employee_id ON attend.face_template (employee_id) INCLUDE (active);
CREATE INDEX ix_face_template_deactivated_at ON attend.face_template (deactivated_at) WHERE NOT active;
CREATE INDEX ix_face_template_review_item_id ON attend.face_template (review_item_id);
CREATE INDEX ix_face_template_created_by ON attend.face_template (created_by);

-- ---------------------------------------------------------------------------------------------------------
-- enrolment_session — ES-nnnnnn. One open session per employee; unsaved captures are deleted with the session.
CREATE TABLE attend.enrolment_session (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                text NOT NULL UNIQUE CHECK (code ~ '^ES-\d{6,}$'),
  employee_id         uuid NOT NULL,
  method              text NOT NULL CHECK (method IN ('LIVE', 'UPLOAD')),
  status              text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED', 'DECLINED', 'EXPIRED')),
  started_by          uuid,
  started_at          timestamptz NOT NULL DEFAULT now(),
  finished_at         timestamptz,
  expires_at          timestamptz NOT NULL,
  consent_id          bigint,
  lookalike_confirmed boolean NOT NULL DEFAULT false,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  version             integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_enrolment_session_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_enrolment_session_started_by FOREIGN KEY (started_by) REFERENCES attend.employee (id),
  CONSTRAINT fk_enrolment_session_consent_id FOREIGN KEY (consent_id) REFERENCES attend.consent (id),
  CONSTRAINT ck_enrolment_session_finished CHECK ((status = 'OPEN') = (finished_at IS NULL))
);
CREATE UNIQUE INDEX uq_enrolment_session_one_open ON attend.enrolment_session (employee_id) WHERE status = 'OPEN';
CREATE INDEX ix_enrolment_session_employee_id ON attend.enrolment_session (employee_id, started_at DESC);
CREATE INDEX ix_enrolment_session_started_by ON attend.enrolment_session (started_by);
CREATE INDEX ix_enrolment_session_consent_id ON attend.enrolment_session (consent_id);
CREATE TRIGGER trg_enrolment_session_updated_at BEFORE UPDATE ON attend.enrolment_session
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

CREATE TABLE attend.enrolment_capture (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  session_id      bigint NOT NULL,
  pose            text CHECK (pose IN ('FRONT', 'TURN_LEFT', 'TURN_RIGHT', 'CHIN_DOWN')),
  image_path      text CHECK (length(image_path) <= 300),
  embedding_enc   bytea,
  quality         jsonb,
  liveness_passed boolean,
  liveness_score  numeric(5,4),
  accepted        boolean NOT NULL,
  reject_reason   text CHECK (length(reject_reason) <= 200),
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_enrolment_capture_session_id FOREIGN KEY (session_id) REFERENCES attend.enrolment_session (id) ON DELETE CASCADE,
  CONSTRAINT ck_enrolment_capture_rejection CHECK (accepted OR reject_reason IS NOT NULL)
);
CREATE INDEX ix_enrolment_capture_session_id ON attend.enrolment_capture (session_id);

-- Liveness lockout: lockFailures failures within lockWindowMin lock enrolment for lockMinutes (NR-02).
CREATE TABLE attend.liveness_failure (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  employee_id uuid NOT NULL,
  session_id  bigint,
  operator_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_liveness_failure_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id),
  CONSTRAINT fk_liveness_failure_session_id FOREIGN KEY (session_id) REFERENCES attend.enrolment_session (id) ON DELETE SET NULL,
  CONSTRAINT fk_liveness_failure_operator_id FOREIGN KEY (operator_id) REFERENCES attend.employee (id)
);
CREATE INDEX ix_liveness_failure_employee_id ON attend.liveness_failure (employee_id, occurred_at DESC);
CREATE INDEX ix_liveness_failure_session_id ON attend.liveness_failure (session_id);
CREATE INDEX ix_liveness_failure_operator_id ON attend.liveness_failure (operator_id);

CREATE TABLE attend.enrolment_lockout (
  employee_id  uuid PRIMARY KEY,
  locked_until timestamptz NOT NULL,
  reason       text CHECK (length(reason) <= 200),
  CONSTRAINT fk_enrolment_lockout_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id)
);

-- ---------------------------------------------------------------------------------------------------------
-- Gallery version triggers. If the worker misses a krz:config message, its 30 s version poll still sees every
-- change that can remove someone from the gallery: erasure (deletes), deactivation, consent withdrawal and
-- account disable.
CREATE TRIGGER trg_face_template_gallery_version
  AFTER INSERT OR UPDATE OR DELETE ON attend.face_template
  FOR EACH STATEMENT EXECUTE FUNCTION attend.bump_gallery_version();
CREATE TRIGGER trg_face_profile_gallery_version
  AFTER INSERT OR UPDATE OR DELETE ON attend.face_profile
  FOR EACH STATEMENT EXECUTE FUNCTION attend.bump_gallery_version();
CREATE TRIGGER trg_consent_gallery_version
  AFTER INSERT OR UPDATE OR DELETE ON attend.consent
  FOR EACH STATEMENT EXECUTE FUNCTION attend.bump_gallery_version();
-- Row level with WHEN: the ORM rewrites every column on update, so only a real change of account_enabled counts.
CREATE TRIGGER trg_employee_gallery_version
  AFTER UPDATE OF account_enabled ON attend.employee
  FOR EACH ROW WHEN (OLD.account_enabled IS DISTINCT FROM NEW.account_enabled)
  EXECUTE FUNCTION attend.bump_gallery_version();

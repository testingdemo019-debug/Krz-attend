-- V001__foundation.sql
-- Purpose: shared building blocks used by every later migration.
-- Tables: worker_sync_state. Functions: set_updated_at(), next_code(), bump_gallery_version().
-- Sequences: one public-code sequence per prefixed entity (see the vocabulary's "Public identifiers").
-- Runs as krz_owner (Flyway). search_path = attend.

-- Flyway created its history table as krz_owner before this script ran, so the default privileges
-- granted it to krz_app. The runtime role must not be able to touch migration history.
REVOKE ALL ON TABLE attend.flyway_schema_history FROM krz_app;

-- ---------------------------------------------------------------------------------------------------------
-- updated_at maintenance (attached as BEFORE UPDATE trigger on every mutable table)
CREATE FUNCTION attend.set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Public codes: prefix + zero-padded sequence value. Values wider than "width" are never truncated
-- (lpad would cut them), so EV-999999 is followed by EV-1000000.
CREATE FUNCTION attend.next_code(prefix text, seq regclass, width int) RETURNS text
LANGUAGE sql VOLATILE AS $$
  SELECT prefix || CASE WHEN length(n) >= width THEN n ELSE lpad(n, width, '0') END
  FROM (SELECT nextval(seq)::text AS n) AS s
$$;
GRANT EXECUTE ON FUNCTION attend.next_code(text, regclass, int) TO krz_app;

CREATE SEQUENCE attend.seq_crossing_code START 100001;          -- EV-100001 (6 digits)
CREATE SEQUENCE attend.seq_review_code START 2001;              -- RV-2001 (4+ digits)
CREATE SEQUENCE attend.seq_audit_code START 1;                  -- AU-000001 (6 digits)
CREATE SEQUENCE attend.seq_outbox_code START 1;                 -- OB-000001 (6 digits)
CREATE SEQUENCE attend.seq_access_request_code START 1;         -- AR-0001 (4 digits)
CREATE SEQUENCE attend.seq_allocation_code START 1;             -- ALC-0001 (4 digits)
CREATE SEQUENCE attend.seq_holiday_code START 1;                -- HOL-<year>-01 (2 digits)
CREATE SEQUENCE attend.seq_privacy_request_code START 1;        -- PR-001 (3+ digits)
CREATE SEQUENCE attend.seq_threshold_profile_code START 1;      -- TP-1
CREATE SEQUENCE attend.seq_sync_run_code START 1;               -- SY-1
CREATE SEQUENCE attend.seq_notification_code START 1;           -- NT-000001 (6 digits)
CREATE SEQUENCE attend.seq_email_code START 1;                  -- EM-000001 (6 digits)
CREATE SEQUENCE attend.seq_entry_point_code START 1;            -- EP-01 (2 digits)
CREATE SEQUENCE attend.seq_camera_code START 1;                 -- CAM-01 (2 digits)
CREATE SEQUENCE attend.seq_enrolment_session_code START 1;      -- ES-000001 (6 digits)

-- ---------------------------------------------------------------------------------------------------------
-- Gallery version: bumped by triggers (added in V006) on every change that can add someone to, or remove
-- someone from, the worker's face gallery. The worker polls it every 30 s through v_worker_config_version.
CREATE TABLE attend.worker_sync_state (
  id              smallint PRIMARY KEY CHECK (id = 1),
  gallery_version bigint NOT NULL,
  changed_at      timestamptz
);
INSERT INTO attend.worker_sync_state (id, gallery_version, changed_at) VALUES (1, 1, now());

CREATE FUNCTION attend.bump_gallery_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  UPDATE attend.worker_sync_state
     SET gallery_version = gallery_version + 1, changed_at = now()
   WHERE id = 1;
  RETURN NULL;
END
$$;

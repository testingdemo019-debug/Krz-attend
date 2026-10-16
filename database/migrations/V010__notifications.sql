-- V010__notifications.sql
-- Purpose: notification rules (NR-01 … NR-11, editable in Settings → Notifications), recipients, in-app
-- notifications (the bell) and the e-mail outbox (Microsoft Graph sendMail).
-- Tables: notification_rule, notification_rule_role, notification_rule_user, notification, email_outbox.

-- notification_rule — push is stored but always forced false while PUSH_ENABLED=false (PENDING P-06).
-- Quiet hours are IST; CRITICAL rules ignore them. digest = DAILY sends one e-mail per user at digest_time (IST).
CREATE TABLE attend.notification_rule (
  code           text PRIMARY KEY CHECK (code ~ '^NR-\d{2}$'),
  label          text NOT NULL CHECK (length(label) <= 120),
  description    text NOT NULL DEFAULT '' CHECK (length(description) <= 500),
  severity       text NOT NULL CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
  enabled        boolean NOT NULL DEFAULT true,
  threshold      integer CHECK (threshold > 0),
  threshold_unit text CHECK (threshold_unit IN ('MINUTES', 'DAYS')),
  params         jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(params) = 'object'),
  in_app         boolean NOT NULL DEFAULT true,
  email          boolean NOT NULL DEFAULT false,
  push           boolean NOT NULL DEFAULT false,
  quiet_start    time,
  quiet_end      time,
  digest         text NOT NULL DEFAULT 'NONE' CHECK (digest IN ('NONE', 'DAILY')),
  digest_time    time,
  updated_by     uuid,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  version        integer NOT NULL DEFAULT 0,
  CONSTRAINT fk_notification_rule_updated_by FOREIGN KEY (updated_by) REFERENCES attend.employee (id),
  CONSTRAINT ck_notification_rule_threshold CHECK ((threshold IS NULL) = (threshold_unit IS NULL)),
  CONSTRAINT ck_notification_rule_quiet CHECK ((quiet_start IS NULL) = (quiet_end IS NULL)),
  CONSTRAINT ck_notification_rule_digest CHECK (digest <> 'DAILY' OR digest_time IS NOT NULL)
);
CREATE INDEX ix_notification_rule_updated_by ON attend.notification_rule (updated_by);
CREATE TRIGGER trg_notification_rule_updated_at BEFORE UPDATE ON attend.notification_rule
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

-- Recipients: roles and/or individual users. Rows belong to the rule; a deleted role drops out of the recipients.
CREATE TABLE attend.notification_rule_role (
  rule_code text NOT NULL,
  role_id   bigint NOT NULL,
  PRIMARY KEY (rule_code, role_id),
  CONSTRAINT fk_notification_rule_role_rule_code FOREIGN KEY (rule_code) REFERENCES attend.notification_rule (code) ON DELETE CASCADE,
  CONSTRAINT fk_notification_rule_role_role_id FOREIGN KEY (role_id) REFERENCES attend.role (id) ON DELETE CASCADE
);
CREATE INDEX ix_notification_rule_role_role_id ON attend.notification_rule_role (role_id);

CREATE TABLE attend.notification_rule_user (
  rule_code   text NOT NULL,
  employee_id uuid NOT NULL,
  PRIMARY KEY (rule_code, employee_id),
  CONSTRAINT fk_notification_rule_user_rule_code FOREIGN KEY (rule_code) REFERENCES attend.notification_rule (code) ON DELETE CASCADE,
  CONSTRAINT fk_notification_rule_user_employee_id FOREIGN KEY (employee_id) REFERENCES attend.employee (id)
);
CREATE INDEX ix_notification_rule_user_employee_id ON attend.notification_rule_user (employee_id);

-- notification — in-app (the bell). link is an app-relative path. dedupe_key prevents repeats per recipient.
CREATE TABLE attend.notification (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code         text NOT NULL UNIQUE CHECK (code ~ '^NT-\d{6,}$'),
  rule_code    text NOT NULL,
  recipient_id uuid NOT NULL,
  title        text NOT NULL CHECK (length(title) <= 200),
  body         text CHECK (length(body) <= 1000),
  link         text CHECK (link ~ '^/[a-z]' AND length(link) <= 300),
  severity     text NOT NULL CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  read_at      timestamptz,
  dedupe_key   text CHECK (length(dedupe_key) <= 200),
  CONSTRAINT fk_notification_rule_code FOREIGN KEY (rule_code) REFERENCES attend.notification_rule (code),
  CONSTRAINT fk_notification_recipient_id FOREIGN KEY (recipient_id) REFERENCES attend.employee (id),
  CONSTRAINT uq_notification_recipient_dedupe UNIQUE (recipient_id, dedupe_key)
);
CREATE INDEX ix_notification_recipient_unread ON attend.notification (recipient_id, read_at, created_at DESC);
CREATE INDEX ix_notification_rule_code ON attend.notification (rule_code);
CREATE INDEX ix_notification_created_at ON attend.notification (created_at);

-- email_outbox — rendered e-mails waiting for (or done with) Graph sendMail. rule_code is NULL for the test e-mail;
-- DIGESTED rows are collected by the digest job into one e-mail per recipient at the rule's digest time.
CREATE TABLE attend.email_outbox (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code            text NOT NULL UNIQUE CHECK (code ~ '^EM-\d{6,}$'),
  rule_code       text,
  recipient_id    uuid,
  to_address      citext NOT NULL CHECK (length(to_address) <= 254),
  template_key    text NOT NULL CHECK (length(template_key) <= 60),
  subject         text NOT NULL CHECK (length(subject) <= 300),
  body_html       text NOT NULL,
  body_text       text NOT NULL,
  status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SUPPRESSED', 'DIGESTED')),
  scheduled_for   timestamptz NOT NULL DEFAULT now(),
  attempts        smallint NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at timestamptz,
  last_error      text CHECK (length(last_error) <= 1000),
  sent_at         timestamptz,
  dedupe_key      text CHECK (length(dedupe_key) <= 200),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_email_outbox_rule_code FOREIGN KEY (rule_code) REFERENCES attend.notification_rule (code),
  CONSTRAINT fk_email_outbox_recipient_id FOREIGN KEY (recipient_id) REFERENCES attend.employee (id),
  CONSTRAINT ck_email_outbox_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);
CREATE INDEX ix_email_outbox_status_due ON attend.email_outbox (status, scheduled_for, next_attempt_at);
CREATE INDEX ix_email_outbox_rule_code ON attend.email_outbox (rule_code);
CREATE INDEX ix_email_outbox_recipient_id ON attend.email_outbox (recipient_id);
CREATE INDEX ix_email_outbox_created_at ON attend.email_outbox (created_at);
CREATE TRIGGER trg_email_outbox_updated_at BEFORE UPDATE ON attend.email_outbox
  FOR EACH ROW EXECUTE FUNCTION attend.set_updated_at();

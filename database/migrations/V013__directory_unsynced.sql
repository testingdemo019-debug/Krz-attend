-- V013__directory_unsynced.sql
-- Purpose: the enabled member accounts of the allowed domain that the directory sync saw in the tenant but in none
-- of the sync-scope groups (ENTRA_SYNC_SCOPE=groups). Settings → Users & roles names them: they cannot be given
-- access until they are added to a sync-scope group in Entra ID. The sync keeps the list current: a later sync that
-- finds them in scope, disabled or deleted removes them, and a full sync drops everyone it no longer saw.
-- Tables: directory_unsynced.

CREATE TABLE attend.directory_unsynced (
  entra_object_id uuid PRIMARY KEY,
  display_name    text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 256),
  upn             citext NOT NULL CHECK (length(upn) <= 254),
  seen_at         timestamptz NOT NULL DEFAULT now()
);

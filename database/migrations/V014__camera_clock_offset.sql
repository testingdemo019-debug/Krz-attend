-- V014__camera_clock_offset.sql
-- Purpose: a camera whose clock was never set (factory date 1970 or 2000) is decades off, and its heartbeat's clock
-- offset (seconds, from ONVIF) did not fit numeric(8,2) (about 11 days), so the whole heartbeat was refused and the
-- camera stayed offline. numeric(12,2) holds any offset up to about 300 years; Settings → Cameras shows how far off
-- the camera's clock is so it can be set.
-- Tables: camera (column clock_offset_s).

ALTER TABLE attend.camera ALTER COLUMN clock_offset_s TYPE numeric(12,2);

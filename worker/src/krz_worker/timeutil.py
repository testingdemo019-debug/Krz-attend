"""The worker's only time helpers. The host clock must be NTP-synced: crossing times come from it."""

from __future__ import annotations

import datetime as dt
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")


def now_utc() -> dt.datetime:
    return dt.datetime.now(dt.UTC)


def ist_day(ts: dt.datetime) -> dt.date:
    """The IST calendar day of an instant (unknown-face clusters, crop folders)."""
    return ts.astimezone(IST).date()


def iso_ms(ts: dt.datetime) -> str:
    """ISO-8601 UTC with milliseconds and ``Z``, as the crossing stream wants it: 2026-10-12T03:50:12.345Z."""
    return ts.astimezone(dt.UTC).strftime("%Y-%m-%dT%H:%M:%S.") + f"{ts.microsecond // 1000:03d}Z"

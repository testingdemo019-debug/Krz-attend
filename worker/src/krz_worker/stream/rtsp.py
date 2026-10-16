"""RTSP URLs, built in memory only, and the one masking helper every log line goes through.

Credentials never appear on a command line, in a log or in an error message: ``mask`` replaces the user and password
of any rtsp:// URL in a text with ``***``.
"""

from __future__ import annotations

import re
from typing import Any
from urllib.parse import quote

import av

_CREDENTIALS = re.compile(r"(rtsps?://)[^\s/@]*@", re.IGNORECASE)

#: PyAV/FFmpeg options: TCP, a 5 s socket timeout and 0.5 s max demux delay (microseconds). ``stimeout`` was removed
#: in FFmpeg 5 and would be ignored silently, leaving a dead camera to block forever.
OPEN_OPTIONS = {"rtsp_transport": "tcp", "timeout": "5000000", "max_delay": "500000"}
#: av.open(timeout=(open, read)) in seconds.
OPEN_TIMEOUT = (5.0, 5.0)


def build_url(host: str, port: int, path: str, username: str | None, password: str | None) -> str:
    credentials = ""
    if username:
        credentials = quote(username, safe="")
        if password:
            credentials += ":" + quote(password, safe="")
        credentials += "@"
    return f"rtsp://{credentials}{host}:{port}{path if path.startswith('/') else '/' + path}"


def mask(text: str) -> str:
    return _CREDENTIALS.sub(r"\1***@", text)


def open_stream(url: str, timeout: tuple[float, float] = OPEN_TIMEOUT) -> Any:
    """An open PyAV container for the URL (raises av.error.* on failure; the caller masks any message it logs)."""
    return av.open(url, options=OPEN_OPTIONS, timeout=timeout)

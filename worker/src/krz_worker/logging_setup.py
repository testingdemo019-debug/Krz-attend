"""JSON logs to stdout and to ``/data/logs/worker/worker.log`` (rotating).

Every line is masked (RTSP credentials). Only the whitelisted context fields are written, so embeddings, images and
names cannot reach the log through ``extra``; crossing lines carry employee IDs only.
"""

from __future__ import annotations

import json
import logging
import logging.handlers
import sys
from pathlib import Path

from krz_worker.stream.rtsp import mask
from krz_worker.timeutil import iso_ms, now_utc

CONTEXT_FIELDS = (
    "camera",
    "crossing_uid",
    "trace_id",
    "decision",
    "employee_id",
    "track_id",
    "error_code",
    "duration_ms",
)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        entry: dict[str, object] = {
            "ts": iso_ms(now_utc()),
            "level": record.levelname,
            "logger": record.name,
            "message": mask(record.getMessage()),
        }
        for field in CONTEXT_FIELDS:
            value = record.__dict__.get(field)
            if value is not None:
                entry[field] = value
        if record.exc_info:
            entry["error"] = mask(self.formatException(record.exc_info))
        return json.dumps(entry, ensure_ascii=False)


def configure(log_dir: Path, level: int = logging.INFO) -> None:
    formatter = JsonFormatter()
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stdout)]
    file_error: OSError | None = None
    try:
        log_dir.mkdir(parents=True, exist_ok=True)
        handlers.append(
            logging.handlers.RotatingFileHandler(
                log_dir / "worker.log", maxBytes=20 * 1024 * 1024, backupCount=5, encoding="utf-8"
            )
        )
    except OSError as error:
        file_error = error
    root = logging.getLogger()
    root.handlers.clear()
    for handler in handlers:
        handler.setFormatter(formatter)
        root.addHandler(handler)
    root.setLevel(level)
    # Third-party libraries log at WARNING and above only.
    for noisy in ("uvicorn.access", "httpx", "urllib3", "psycopg.pool"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    if file_error is not None:
        logging.getLogger(__name__).warning("Log file unavailable, stdout only: %s", file_error)

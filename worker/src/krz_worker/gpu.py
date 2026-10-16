"""The GPU as /health and the worker heartbeat report it: name, memory and utilisation from ``nvidia-smi`` (present in
the NVIDIA runtime image and on hosts with the driver), cached for 10 s; None without a GPU."""

from __future__ import annotations

import shutil
import subprocess
import threading
import time
from typing import Any

CACHE_SECONDS = 10.0


class _Cache:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.at = 0.0
        self.value: dict[str, Any] | None = None


_cache = _Cache()


def info(device: int) -> dict[str, Any] | None:
    with _cache.lock:
        if time.monotonic() - _cache.at >= CACHE_SECONDS:
            _cache.value, _cache.at = _query(device), time.monotonic()
        return _cache.value


def _query(device: int) -> dict[str, Any] | None:
    binary = shutil.which("nvidia-smi")
    if binary is None:
        return None
    try:
        output = subprocess.run(  # noqa: S603 - fixed binary and arguments
            [
                binary,
                f"--id={device}",
                "--query-gpu=name,memory.used,memory.total,utilization.gpu",
                "--format=csv,noheader,nounits",
            ],
            capture_output=True,
            text=True,
            timeout=2,
            check=True,
        ).stdout.strip()
        name, used, total, util = (part.strip() for part in output.split(","))
        return {"name": name, "mem_used_mb": int(used), "mem_total_mb": int(total), "util_pct": int(util)}
    except (OSError, subprocess.SubprocessError, ValueError):
        return None

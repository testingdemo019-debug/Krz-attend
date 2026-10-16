"""Redis as the worker uses it (Redis contract §1-§4): crossings onto ``krz:crossings``, overlays and heartbeats out,
``krz:config`` in. Connected as ``krz_worker``, whose ACL allows exactly these commands and keys.

When Redis cannot take a crossing it goes to the spool: ``/data/spool/crossings-<IST day>.jsonl``, one fsync'd line per
message. While anything is spooled, new crossings are appended too, so the replayer sends everything in order. The
backend is idempotent on ``crossing_uid``, so a line sent twice after a crash is harmless. A spool file is deleted only
once all its lines are sent.
"""

from __future__ import annotations

import contextlib
import json
import logging
import os
import threading
from collections.abc import Callable
from pathlib import Path
from typing import Any

import redis

from krz_worker.config import Settings
from krz_worker.timeutil import ist_day, now_utc

LOG = logging.getLogger(__name__)

STREAM = "krz:crossings"
STREAM_MAXLEN = 100_000
CONFIG_CHANNEL = "krz:config"
OVERLAY_PREFIX = "krz:overlay:"
WORKER_HEARTBEAT = "krz:hb:worker:"
CAMERA_HEARTBEAT = "krz:hb:camera:"


class Spool:
    def __init__(self, directory: Path) -> None:
        self._dir = directory
        self._lock = threading.RLock()

    def files(self) -> list[Path]:
        return sorted(self._dir.glob("crossings-*.jsonl")) if self._dir.is_dir() else []

    def pending(self) -> bool:
        with self._lock:
            return bool(self.files())

    def append(self, fields: dict[str, str]) -> None:
        with self._lock:
            self._dir.mkdir(parents=True, exist_ok=True)
            path = self._dir / f"crossings-{ist_day(now_utc()).isoformat()}.jsonl"
            with path.open("a", encoding="utf-8") as out:
                out.write(json.dumps(fields, separators=(",", ":")) + "\n")
                out.flush()
                os.fsync(out.fileno())
            LOG.warning("Crossing spooled (Redis unavailable)", extra={"crossing_uid": fields.get("crossing_uid")})

    def replay(self, send: Callable[[dict[str, str]], None]) -> int:
        """Send every spooled line, oldest file first; stop at the first failure and keep what is left."""
        sent = 0
        with self._lock:
            for path in self.files():
                lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
                for index, line in enumerate(lines):
                    try:
                        send(json.loads(line))
                    except redis.RedisError:
                        _rewrite(path, lines[index:])
                        return sent
                    sent += 1
                path.unlink()
        if sent:
            LOG.info("Replayed %d spooled crossings", sent)
        return sent


def _rewrite(path: Path, lines: list[str]) -> None:
    temp = path.with_suffix(".part")
    with temp.open("w", encoding="utf-8") as out:
        out.write("\n".join(lines) + "\n")
        out.flush()
        os.fsync(out.fileno())
    os.replace(temp, path)


class RedisBus:
    def __init__(self, settings: Settings) -> None:
        self.client = redis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            username=settings.redis_user,
            password=settings.redis_password.get_secret_value(),
            client_name=f"krz-worker-{settings.worker_id}",
            socket_timeout=3,
            socket_connect_timeout=3,
            health_check_interval=30,
        )
        self.spool = Spool(settings.spool_dir)
        self._pubsub: Any = None
        self._listener: Any = None

    def send_crossing(self, fields: dict[str, str]) -> None:
        """XADD the crossing, or spool it (also while older crossings wait in the spool, to keep the order)."""
        if self.spool.pending():
            self.spool.append(fields)
            return
        try:
            self._xadd(fields)
        except redis.RedisError:
            self.spool.append(fields)

    def _xadd(self, fields: dict[str, str]) -> None:
        self.client.xadd(STREAM, fields, maxlen=STREAM_MAXLEN, approximate=True)  # type: ignore[arg-type]

    def replay_spool(self) -> int:
        if not self.spool.pending():
            return 0
        try:
            self.client.ping()
        except redis.RedisError:
            return 0
        return self.spool.replay(self._xadd)

    def publish_overlay(self, camera: str, payload: dict[str, Any]) -> None:
        """Best effort: an overlay frame that cannot be sent is simply dropped."""
        with contextlib.suppress(redis.RedisError):
            self.client.publish(OVERLAY_PREFIX + camera, json.dumps(payload, separators=(",", ":")))

    def heartbeat(self, key: str, payload: dict[str, Any], ttl_seconds: int) -> None:
        try:
            self.client.set(key, json.dumps(payload, separators=(",", ":")), ex=ttl_seconds)
        except redis.RedisError as error:
            LOG.debug("Heartbeat %s not written: %s", key, error)

    def listen_config(self, handler: Callable[[dict[str, Any]], None]) -> None:
        """Calls ``handler`` with every ``krz:config`` signal, on a background thread that survives Redis outages."""

        def on_message(message: dict[str, Any]) -> None:
            try:
                handler(json.loads(message["data"]))
            except (ValueError, TypeError):
                LOG.warning("Ignored an unreadable krz:config message")

        def on_error(error: BaseException, pubsub: Any, thread: Any) -> None:
            LOG.warning("krz:config listener: %s; resubscribing", type(error).__name__)

        self._pubsub = self.client.pubsub(ignore_subscribe_messages=True)  # type: ignore[no-untyped-call]
        self._pubsub.subscribe(**{CONFIG_CHANNEL: on_message})
        self._listener = self._pubsub.run_in_thread(sleep_time=1.0, daemon=True, exception_handler=on_error)

    def close(self) -> None:
        if self._listener is not None:
            self._listener.stop()
        if self._pubsub is not None:
            self._pubsub.close()
        self.client.close()

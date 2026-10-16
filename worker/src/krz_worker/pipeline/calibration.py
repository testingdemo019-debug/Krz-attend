"""Calibration "test walks": while a run is active for a camera, its crossings are recorded here for the caller
(Settings -> Cameras) instead of being sent to the stream, so no punches are created."""

from __future__ import annotations

import datetime as dt
import threading
import uuid
from dataclasses import dataclass, field

from krz_worker.timeutil import iso_ms, now_utc

MAX_SECONDS = 600


@dataclass(frozen=True)
class CalibrationCrossing:
    ts: dt.datetime
    direction: str
    #: The person detector's confidence for the track when the crossing was confirmed.
    confidence: float
    frames: int
    u_turn: bool

    def as_dict(self) -> dict[str, object]:
        return {
            "ts": iso_ms(self.ts),
            "direction": self.direction,
            "confidence": round(self.confidence, 3),
            "frames": self.frames,
            "u_turn": self.u_turn,
        }


@dataclass
class Run:
    run_id: str
    camera: str
    until: dt.datetime
    crossings: list[CalibrationCrossing] = field(default_factory=list)

    def status(self, now: dt.datetime) -> str:
        return "running" if now < self.until else "completed"


class Calibrations:
    def __init__(self) -> None:
        self._runs: dict[str, Run] = {}
        self._lock = threading.Lock()

    def start(self, camera: str, seconds: int) -> Run:
        run = Run(uuid.uuid4().hex, camera, now_utc() + dt.timedelta(seconds=min(seconds, MAX_SECONDS)))
        with self._lock:
            # Finished runs of the last hour are kept for polling; older ones go.
            cutoff = now_utc() - dt.timedelta(hours=1)
            self._runs = {k: r for k, r in self._runs.items() if r.until > cutoff}
            self._runs[run.run_id] = run
        return run

    def active(self, camera: str, at: dt.datetime) -> Run | None:
        with self._lock:
            return next((r for r in self._runs.values() if r.camera == camera and at < r.until), None)

    def get(self, camera: str, run_id: str) -> Run | None:
        with self._lock:
            run = self._runs.get(run_id)
        return run if run is not None and run.camera == camera else None

    def record(self, run: Run, crossing: CalibrationCrossing) -> None:
        with self._lock:
            run.crossings.append(crossing)

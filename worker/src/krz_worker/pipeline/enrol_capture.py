"""Capture at entry point (D-118): face templates taken by the attendance camera itself.

While the backend runs a capture for a camera, the face samples of the one person in view go into four angle groups
(FRONT, TURN_LEFT, TURN_RIGHT, CHIN_DOWN, as the live enrolment's poses) and the best ones that meet the backend's
enrolment bar are kept, up to ``PER_GROUP`` per group from different moments. When the backend stops the capture it
receives them (face crop JPEG, embedding, quality, angle, light, liveness); nothing is written to disk.

One person only: frames with more than one person in view give no samples (the status says so), and every sample must
look like the first one kept (cosine at least ``SAME_PERSON``), so someone else walking through alone is not captured
either. The camera pipeline marks the tracks seen alone during the capture, and those that gave a sample, so their
crossings are recorded without being counted (the operator has the person walk through a few times).
"""

from __future__ import annotations

import base64
import datetime as dt
import threading
from dataclasses import dataclass, field
from typing import Any

import cv2
import numpy as np

from krz_worker.models.base import Image, Liveness, Vectors
from krz_worker.recognition.quality import Quality
from krz_worker.timeutil import IST, iso_ms, now_utc

GROUPS = ("FRONT", "TURN_LEFT", "TURN_RIGHT", "CHIN_DOWN")
#: Samples kept per angle group (the operator chooses which to save).
PER_GROUP = 2
#: A second sample of the same group must be this much later than the first (another moment, not the next frame).
MIN_GAP = dt.timedelta(seconds=1)
MAX_SECONDS = 180
#: Head turn (degrees, positive = turned to the person's left) from which a face counts as turned.
TURN_MIN = 12.0
#: Within this turn a face is face-on (or chin down when the pitch says so).
FRONT_MAX = 10.0
#: Pitch (degrees, positive = chin down) from which a face-on sample is CHIN_DOWN.
CHIN_DOWN_MIN = 15.0
#: Cosine similarity a sample needs to the first kept one (SFace; different people stay well below).
SAME_PERSON = 0.40
#: Mean saturation (0-255) below which a crop is grey: the camera is in infrared night mode.
IR_SATURATION = 12.0
#: "crowded" in the status stays on this long after the last frame with more than one person.
CROWDED_HOLD = dt.timedelta(seconds=2)
JPEG_QUALITY = 92


@dataclass(frozen=True)
class CaptureRules:
    """The backend's bar for a template: Settings -> Enrolment & quality, and the recognition profile's max turn."""

    min_eye_px: int
    min_quality: int
    min_sharp: int
    max_yaw: float


@dataclass(frozen=True)
class Sample:
    group: str
    quality: Quality
    jpeg: bytes
    embedding: Vectors
    model: str
    at: dt.datetime
    light: str
    liveness: Liveness | None

    def as_dict(self) -> dict[str, Any]:
        vector = np.asarray(self.embedding, dtype="<f4")
        return {
            "group": self.group,
            "jpeg": base64.b64encode(self.jpeg).decode(),
            "embedding": base64.b64encode(vector.tobytes()).decode(),
            "dim": int(vector.shape[0]),
            "model": self.model,
            "quality": {
                "eye_px": self.quality.eye_px,
                "yaw": self.quality.yaw,
                "pitch": self.quality.pitch,
                "sharp": self.quality.sharp,
                "brightness": self.quality.brightness,
                "score": self.quality.score,
            },
            "light": self.light,
            "liveness": None
            if self.liveness is None
            else {"passed": self.liveness.passed, "score": round(self.liveness.score, 4)},
            "at": iso_ms(self.at),
        }


def group_of(quality: Quality, max_yaw: float) -> str | None:
    """The angle group of a face, or None when it is turned too far or between groups."""
    yaw, pitch = quality.yaw, quality.pitch
    if abs(yaw) > max_yaw:
        return None
    if yaw >= TURN_MIN:
        return "TURN_LEFT"
    if yaw <= -TURN_MIN:
        return "TURN_RIGHT"
    if abs(yaw) <= FRONT_MAX:
        return "CHIN_DOWN" if pitch >= CHIN_DOWN_MIN else "FRONT"
    return None


def light_of(crop: Image, at: dt.datetime) -> str:
    """NIGHT_IR for a grey (infrared) picture; otherwise DAY from 06:00 to 18:00 IST, else EVENING."""
    if crop.size and float(cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)[..., 1].mean()) < IR_SATURATION:
        return "NIGHT_IR"
    hour = at.astimezone(IST).hour
    return "DAY" if 6 <= hour < 18 else "EVENING"


def encode_jpeg(crop: Image) -> bytes | None:
    ok, jpeg = cv2.imencode(".jpg", crop, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
    return jpeg.tobytes() if ok else None


def _unit(vector: Vectors) -> Vectors:
    v = np.asarray(vector, dtype=np.float32).reshape(-1)
    norm = float(np.linalg.norm(v))
    result: Vectors = v / norm if norm > 0 else v
    return result


@dataclass
class Capture:
    session: str
    camera: str
    rules: CaptureRules
    started_at: dt.datetime
    until: dt.datetime
    stopped: bool = False
    samples: dict[str, list[Sample]] = field(default_factory=lambda: {g: [] for g in GROUPS})
    people: int = 0
    crowded_at: dt.datetime | None = None
    #: Samples refused because they did not look like the first one kept.
    other_person: int = 0
    _reference: Vectors | None = None
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def running(self, now: dt.datetime) -> bool:
        return not self.stopped and now < self.until

    def frame(self, people: int, at: dt.datetime) -> bool:
        """One analysed frame: how many people are in view. Returns whether samples may be taken (exactly one)."""
        with self._lock:
            self.people = people
            if people > 1:
                self.crowded_at = at
            return people == 1

    def wants(self, quality: Quality, at: dt.datetime) -> str | None:
        """The group this face would join (before the costly embedding, crop and liveness), or None."""
        rules = self.rules
        if quality.eye_px < rules.min_eye_px or quality.score < rules.min_quality or quality.sharp < rules.min_sharp:
            return None
        group = group_of(quality, rules.max_yaw)
        if group is None:
            return None
        with self._lock:
            kept = self.samples[group]
            if len(kept) < PER_GROUP:
                return group if all(abs(at - s.at) >= MIN_GAP for s in kept) else None
            worst = min(kept, key=lambda s: s.quality.score)
            if quality.score <= worst.quality.score:
                return None
            others = [s for s in kept if s is not worst]
            return group if all(abs(at - s.at) >= MIN_GAP for s in others) else None

    def offer(self, sample: Sample) -> bool:
        """Keeps the sample if it is the same person and among the best of its group."""
        unit = _unit(sample.embedding)
        with self._lock:
            if self._reference is not None and float(unit @ self._reference) < SAME_PERSON:
                self.other_person += 1
                return False
            kept = self.samples[sample.group]
            if len(kept) >= PER_GROUP:
                kept.remove(min(kept, key=lambda s: s.quality.score))
            kept.append(sample)
            kept.sort(key=lambda s: s.quality.score, reverse=True)
            if self._reference is None:
                self._reference = unit
            return True

    def status(self, now: dt.datetime) -> dict[str, Any]:
        with self._lock:
            return {
                "session": self.session,
                "status": "stopped" if self.stopped else ("running" if now < self.until else "completed"),
                "seconds_left": max(0, round((self.until - now).total_seconds())) if not self.stopped else 0,
                "people": self.people,
                "crowded": self.crowded_at is not None and now - self.crowded_at <= CROWDED_HOLD,
                "other_person": self.other_person,
                "groups": {
                    g: [
                        {
                            "score": s.quality.score,
                            "eye_px": s.quality.eye_px,
                            "yaw": s.quality.yaw,
                            "pitch": s.quality.pitch,
                            "light": s.light,
                        }
                        for s in kept
                    ]
                    for g, kept in self.samples.items()
                },
            }

    def result(self) -> list[dict[str, Any]]:
        with self._lock:
            return [s.as_dict() for g in GROUPS for s in self.samples[g]]


class Captures:
    """The captures by camera: at most one per camera; a stopped or finished one is kept until it is collected."""

    def __init__(self) -> None:
        self._by_camera: dict[str, Capture] = {}
        self._lock = threading.Lock()

    def start(self, camera: str, session: str, seconds: int, rules: CaptureRules) -> Capture:
        now = now_utc()
        capture = Capture(session, camera, rules, now, now + dt.timedelta(seconds=min(seconds, MAX_SECONDS)))
        with self._lock:
            self._by_camera[camera] = capture
        return capture

    def active(self, camera: str, at: dt.datetime) -> Capture | None:
        with self._lock:
            capture = self._by_camera.get(camera)
        return capture if capture is not None and capture.running(at) else None

    def get(self, camera: str, session: str) -> Capture | None:
        with self._lock:
            capture = self._by_camera.get(camera)
        return capture if capture is not None and capture.session == session else None

    def stop(self, camera: str, session: str) -> Capture | None:
        """Ends the capture and hands it over (it is forgotten here)."""
        with self._lock:
            capture = self._by_camera.get(camera)
            if capture is None or capture.session != session:
                return None
            del self._by_camera[camera]
        capture.stopped = True
        return capture

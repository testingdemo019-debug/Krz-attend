"""What the worker knows about one tracked person while they are in view: where they are relative to the tripwire,
the face votes, the liveness checks, the best face and body crops, the body embeddings and how they look in the
overlay."""

from __future__ import annotations

import datetime as dt
from collections import deque
from dataclasses import dataclass, field

from krz_worker.models.base import Face, Image, Liveness, Vectors
from krz_worker.recognition.decision import Evidence, Thresholds, Vote, decide
from krz_worker.recognition.quality import Quality
from krz_worker.tracking.tripwire import Direction, LineState

#: Body embeddings kept per track (the best by box height).
BODIES_KEPT = 5
VOTES_KEPT = 10


@dataclass
class FaceSample:
    quality: Quality
    passed: bool
    #: The face cut from the full-resolution frame with a 25 % margin (encrypted into the crossing's face crop).
    crop: Image
    #: The aligned face's embedding, for unknown-face clustering; None when the face failed the gate.
    embedding: Vectors | None


@dataclass
class BodySample:
    height: float
    crop: Image
    #: The face box inside ``crop`` (pixelated in the blurred variant), when the head was found.
    face_in_crop: tuple[float, float, float, float] | None


@dataclass
class TrackState:
    track_id: int
    first_seen: dt.datetime
    last_seen: dt.datetime
    box: tuple[float, float, float, float] = (0.0, 0.0, 0.0, 0.0)
    line: LineState = field(default_factory=LineState)
    votes: deque[Vote] = field(default_factory=lambda: deque(maxlen=VOTES_KEPT))
    liveness: deque[Liveness] = field(default_factory=lambda: deque(maxlen=3))
    best_face: FaceSample | None = None
    best_body: BodySample | None = None
    bodies: list[tuple[float, Vectors]] = field(default_factory=list)
    last_face_check: int = -1_000
    last_face_height: float = 0.0
    last_face: Face | None = None
    last_body_check: int = -1_000
    #: The last confirmed crossing's direction (overlay arrow).
    direction: Direction | None = None
    #: Shown once the crossing assigned an unknown-face cluster, e.g. "Unknown #A3".
    unknown_label: str | None = None

    def offer_face(self, sample: FaceSample) -> None:
        """Keep the best face: one that passed the gate beats one that did not, then the higher quality."""
        current = self.best_face
        if current is None or (sample.passed, sample.quality.score) > (current.passed, current.quality.score):
            self.best_face = sample

    def offer_body(self, height: float, embedding: Vectors | None, sample: BodySample | None) -> None:
        if embedding is not None:
            self.bodies.append((height, embedding))
            self.bodies.sort(key=lambda pair: pair[0], reverse=True)
            del self.bodies[BODIES_KEPT:]
        if sample is not None and (self.best_body is None or sample.height > self.best_body.height):
            self.best_body = sample

    def evidence(self, direction: Direction, liveness_on: bool) -> Evidence:
        return Evidence(direction, tuple(self.votes), tuple(self.liveness) if liveness_on else (), liveness_on)

    def overlay(self, thresholds: Thresholds, liveness_on: bool, names: dict[str, str]) -> tuple[str, str, str | None]:
        """(label, state, confidence band) for the overlay while the person is in view."""
        if not self.votes:
            if self.unknown_label:
                return self.unknown_label, "unknown", None
            return ("Checking…", "checking", None) if self.last_face is not None else ("Back view", "reid", None)
        decision = decide(self.evidence(self.direction or "IN", liveness_on), thresholds)
        band = thresholds.band(decision.score) if decision.score is not None else None
        if decision.hint == "LIVENESS_FAIL":
            return "Liveness check", "liveness", None
        if decision.hint == "AUTO" and decision.employee_id is not None:
            return names.get(decision.employee_id, "Identified"), "identified", band
        if decision.hint == "UNKNOWN" and len(self.votes) >= thresholds.agree_n:
            return self.unknown_label or "Unknown", "unknown", band
        return "Checking…", "checking", band

"""The face quality gate (R14) and the overlay's quality badge.

Measured on a detected face (landmarks) and its aligned 112x112 crop:
- ``eye_px``: distance between the eyes in frame pixels;
- ``yaw`` / ``pitch``: estimated from the landmarks (degrees; 0 = facing the camera);
- ``sharp``: variance of the Laplacian of the aligned face, scaled to 0-100 (``SHARP_FULL`` and above = 100);
- ``brightness``: mean grey level 0-255, and ``light``: 0-100, how evenly exposed (100 = mid-grey);
- ``quality`` / ``score``: one 0-100 figure combining size, pose, sharpness, light and the detector's confidence.

The scaling constants are first estimates; they are calibrated on real footage with the recognition thresholds
(PENDING P-11).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import cv2
import numpy as np

from krz_worker.models.base import Face, Image

#: Laplacian variance of a crisp aligned face; at and above it sharpness is 100.
SHARP_FULL = 400.0
#: Eye distance at which the size part of the score is full (twice the usual gate).
EYE_FULL = 70.0


@dataclass(frozen=True)
class GateThresholds:
    min_eye_px: int
    max_yaw: int
    sharp_min: int
    quality_min: int


@dataclass(frozen=True)
class Quality:
    eye_px: int
    yaw: float
    pitch: float
    sharp: int
    brightness: int
    score: int
    failures: tuple[str, ...] = field(default=())

    @property
    def passed(self) -> bool:
        return not self.failures

    @property
    def light(self) -> int:
        """Exposure 0-100: 100 at mid-grey, falling to 0 at black or white (the Lighting chip, D-092)."""
        return round(100 * light_part(self.brightness))

    def as_message(self) -> dict[str, float | int]:
        """The crossing message's ``quality`` JSON: {eye_px, yaw, pitch, sharp, quality, light}."""
        return {
            "eye_px": self.eye_px,
            "yaw": self.yaw,
            "pitch": self.pitch,
            "sharp": self.sharp,
            "quality": self.score,
            "light": self.light,
        }

    def badge(self, quality_min: int) -> str:
        """The overlay badge: good, fair or poor."""
        if not self.passed:
            return "poor"
        return "good" if self.score >= quality_min + 20 else "fair"


def light_part(brightness: float) -> float:
    """How evenly exposed a face is, 0-1: 1 at mid-grey (128)."""
    return max(0.0, 1 - abs(brightness - 128) / 128)


def measure(face: Face, aligned: Image, gate: GateThresholds | None = None) -> Quality:
    """Measure a face; with ``gate``, also list which thresholds it fails."""
    (rx, ry), (lx, ly), (nx, ny), (_, rmy), (_, lmy) = face.landmarks
    eye_px = math.hypot(lx - rx, ly - ry)
    eye_mid_x, eye_mid_y = (rx + lx) / 2, (ry + ly) / 2
    mouth_mid_y = (rmy + lmy) / 2
    # The nose drifts sideways from the eyes' midpoint as the head turns: half an eye distance ~ 45 degrees.
    yaw = max(-90.0, min(90.0, 90.0 * (nx - eye_mid_x) / max(eye_px, 1.0)))
    # Facing the camera, the nose sits a little over halfway from the eyes to the mouth.
    vertical = (ny - eye_mid_y) / max(mouth_mid_y - eye_mid_y, 1.0)
    pitch = max(-90.0, min(90.0, (vertical - 0.55) * 120.0))
    grey = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)
    laplacian = float(cv2.Laplacian(grey, cv2.CV_64F).var())
    sharp = round(100 * min(1.0, laplacian / SHARP_FULL))
    brightness = round(float(np.mean(grey)))
    size_part = min(1.0, eye_px / EYE_FULL)
    pose_part = max(0.0, 1 - (abs(yaw) + abs(pitch) / 2) / 90)
    score = round(
        100
        * (0.25 * size_part + 0.25 * pose_part + 0.25 * sharp / 100 + 0.1 * light_part(brightness) + 0.15 * face.score)
    )
    failures: list[str] = []
    if gate is not None:
        if eye_px < gate.min_eye_px:
            failures.append("face too small")
        if abs(yaw) > gate.max_yaw:
            failures.append("turned away")
        if sharp < gate.sharp_min:
            failures.append("blurred")
        if score < gate.quality_min:
            failures.append("low quality")
    return Quality(round(eye_px), round(yaw, 1), round(pitch, 1), sharp, brightness, score, tuple(failures))

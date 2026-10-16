"""ByteTrack (MIT) from ``supervision``: stable track IDs for the people detected in each frame.

supervision deprecates its ByteTrack (removal announced for 0.31); it is pinned at 0.30.9 and wrapped here, so moving
to another ByteTrack implementation later changes this file only (PENDING P-28).
"""

from __future__ import annotations

import warnings

import numpy as np
import supervision as sv

from krz_worker.models.base import Person


class PersonTracker:
    def __init__(self, frame_rate: float) -> None:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", FutureWarning)
            self._tracker = sv.ByteTrack(frame_rate=max(1, round(frame_rate)), lost_track_buffer=int(2 * frame_rate))

    def update(self, people: list[Person]) -> list[tuple[int, Person]]:
        """The tracked people of this frame with their track IDs (new detections may wait a frame for an ID)."""
        if people:
            xyxy = np.array([p.box for p in people], dtype=np.float32)
            confidence = np.array([p.score for p in people], dtype=np.float32)
        else:
            xyxy = np.zeros((0, 4), dtype=np.float32)
            confidence = np.zeros((0,), dtype=np.float32)
        detections = sv.Detections(xyxy=xyxy, confidence=confidence, class_id=np.zeros(len(people), dtype=int))
        tracked = self._tracker.update_with_detections(detections)
        if tracked.tracker_id is None or tracked.confidence is None:
            return []
        return [
            (int(track_id), Person((float(box[0]), float(box[1]), float(box[2]), float(box[3])), float(score)))
            for box, score, track_id in zip(tracked.xyxy, tracked.confidence, tracked.tracker_id, strict=True)
        ]

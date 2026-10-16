"""Live overlays (Redis contract §2): about 8 times a second, every track's normalised box, label, state, ID,
direction, confidence band and face-quality badge, plus who is in view. Tripwire and zone come from the camera
configuration, not from here. Never a numeric score."""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

from krz_worker.timeutil import iso_ms, now_utc

INTERVAL = 1 / 8


@dataclass(frozen=True)
class OverlayTrack:
    track_id: int
    box: tuple[float, float, float, float]
    label: str
    state: str
    direction: str | None
    conf_band: str | None
    quality: str | None


def payload(camera: str, width: int, height: int, tracks: list[OverlayTrack], fps: float | None) -> dict[str, Any]:
    return {
        "v": 1,
        "camera": camera,
        "frame_ts": iso_ms(now_utc()),
        "w": width,
        "h": height,
        "tracks": [
            {
                "id": t.track_id,
                "bbox": [
                    round(t.box[0] / width, 4),
                    round(t.box[1] / height, 4),
                    round((t.box[2] - t.box[0]) / width, 4),
                    round((t.box[3] - t.box[1]) / height, 4),
                ],
                "label": t.label,
                "state": t.state,
                "direction": t.direction,
                "conf_band": t.conf_band,
                "quality": t.quality,
            }
            for t in tracks
        ],
        "in_view": {
            "count": len(tracks),
            "names": sorted({t.label for t in tracks if t.state == "identified"}),
        },
        "fps": fps,
    }


class Throttle:
    """Lets one overlay through per interval."""

    def __init__(self, interval: float = INTERVAL) -> None:
        self._interval = interval
        self._next = 0.0

    def ready(self) -> bool:
        now = time.monotonic()
        if now < self._next:
            return False
        self._next = now + self._interval
        return True

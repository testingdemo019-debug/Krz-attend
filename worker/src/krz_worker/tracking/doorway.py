"""Doorway zone counting (pure, D-120): IN/OUT by where a walk starts and ends, for compact entrances where the floor
in front of the door is out of view and a tripwire would sit at the very edge of the picture.

A camera counted this way has a door area (a normalised polygon drawn tightly around the door opening) instead of a
tripwire. Each track's walk keeps its first and last few boxes; once the person has gone, each end of the walk is
placed:

- **DOOR**: mostly inside the door area and narrower than the door, so still in the doorway (a person close to the
  camera in front of the door is wider than it);
- **ROOM**: at an edge of the picture or of the detection zone, or wide in front of the door (close to the camera);
- **MIDDLE**: anywhere else (lost behind another person or the door leaf).

An end is ROOM when any of its boxes is, else DOOR when any is. Door -> room is IN and room -> door is OUT, whichever
edge the room side was; door -> door (opened the door and went back, or walked past outside the glass) and
room -> room (walking about inside) are not crossings. A walk with one end in the middle counts as the same direction,
less certainly. Two checks confirm it: the size (coming in, people grow; going out, they shrink) and the side seen (the
face is seen walking towards the camera). A walk no check supports is still counted, with the most likely direction,
and marked "direction unsure" so it can be checked and flipped in Events.
"""

from __future__ import annotations

import datetime as dt
import math
import statistics
from collections import deque
from dataclasses import dataclass, field
from typing import Literal

from krz_worker.tracking import zone
from krz_worker.tracking.tripwire import Direction

Box = tuple[float, float, float, float]
Point = tuple[float, float]
Place = Literal["DOOR", "ROOM", "MIDDLE"]
FacesVisibleWhen = Literal["ENTERING", "LEAVING"]

#: Boxes kept at each end of a walk (0.2 s at 25 fps).
ENDS = 5
#: A walk seen in fewer frames is too short to judge.
MIN_FRAMES = 3
#: Share of a box inside the door area for "in the doorway", and the widest a person in the doorway is (of the door).
DOOR_SHARE = 0.6
DOOR_WIDTH = 0.9
#: How close to an edge of the picture (of its width or height) or of the detection zone counts as at that edge.
EDGE = 0.012
ZONE_BORDER = 0.03
#: End size over start size: from this much bigger it grew (towards the camera); from its inverse it shrank.
GROWS = 1.25
#: Face checks of a big enough person: the face found in this share of them is the front; none in this many is the back.
FRONT_SHARE = 0.3
BACK_CHECKS = 4
#: How long after the last sighting a walk is judged: soon when it ended at the door or an edge, later when it ended
#: in the middle (ByteTrack may still pick the person up again for two seconds).
GONE = dt.timedelta(seconds=0.8)
GONE_IN_MIDDLE = dt.timedelta(seconds=2.5)

#: (start, end) -> (direction, both ends certain).
_PATHS: dict[tuple[Place, Place], tuple[Direction, bool]] = {
    ("DOOR", "ROOM"): ("IN", True),
    ("ROOM", "DOOR"): ("OUT", True),
    ("DOOR", "MIDDLE"): ("IN", False),
    ("MIDDLE", "DOOR"): ("OUT", False),
}


@dataclass(frozen=True)
class Area:
    """A camera's door area and detection zone (normalised polygons; an empty zone is the whole picture)."""

    door: tuple[Point, ...]
    zone: tuple[Point, ...] = ()


@dataclass(frozen=True)
class DoorwayCrossing:
    direction: Direction
    #: IN: when the person appeared in the doorway; OUT: when they disappeared through it.
    at: dt.datetime
    frames: int
    #: No check supports the direction (sent as ``direction_unsure``).
    unsure: bool
    start: Place
    end: Place


@dataclass
class Walk:
    """One track's walk, from its first sighting: the boxes at both ends and which side of the person was seen."""

    first: list[tuple[dt.datetime, Box]] = field(default_factory=list)
    last: deque[tuple[dt.datetime, Box]] = field(default_factory=lambda: deque(maxlen=ENDS))
    frames: int = 0
    #: The person detector's best confidence during the walk (reported by calibration test walks).
    confidence: float = 0.0
    face_checks: int = 0
    faces_found: int = 0

    def add(self, ts: dt.datetime, box: Box, confidence: float = 0.0) -> None:
        if len(self.first) < ENDS:
            self.first.append((ts, box))
        self.last.append((ts, box))
        self.frames += 1
        self.confidence = max(self.confidence, confidence)

    def face_checked(self, found: bool) -> None:
        """A face check of the person while they were big enough to show one."""
        self.face_checks += 1
        self.faces_found += int(found)


def judge(walk: Walk, area: Area, width: int, height: int, faces: FacesVisibleWhen) -> DoorwayCrossing | None:
    """The crossing this walk was, or None (too short, door -> door, room -> room or never near the door)."""
    if walk.frames < MIN_FRAMES or len(area.door) < 3:
        return None
    start = _end([box for _, box in walk.first], area, width, height)
    end = _end([box for _, box in walk.last], area, width, height)
    path = _PATHS.get((start, end))
    if path is None:
        return None
    direction, certain = path
    checks = [c for c in (_size_check(walk), _side_check(walk, faces)) if c is not None]
    support = sum(c == direction for c in checks)
    against = len(checks) - support
    unsure = (against > 0 and support == 0) if certain else (support == 0 or against > 0)
    at = walk.first[0][0] if direction == "IN" else walk.last[-1][0]
    return DoorwayCrossing(direction, at, walk.frames, unsure, start, end)


def wait_after(walk: Walk, area: Area, width: int, height: int) -> dt.timedelta:
    """How long after the last sighting to judge the walk."""
    if not walk.last:
        return GONE
    return GONE_IN_MIDDLE if _end([box for _, box in walk.last], area, width, height) == "MIDDLE" else GONE


def place(box: Box, area: Area, width: int, height: int) -> Place:
    """Where one box is: in the doorway, at the room side, or in the middle."""
    x1, y1, x2, y2 = box
    share = _door_share(box, area.door, width, height)
    if share >= DOOR_SHARE and (x2 - x1) <= DOOR_WIDTH * _door_width(area.door, width):
        return "DOOR"
    if x1 <= EDGE * width or y1 <= EDGE * height or x2 >= (1 - EDGE) * width or y2 >= (1 - EDGE) * height:
        return "ROOM"
    if len(area.zone) >= 3 and _border_distance(zone.anchor(box, width, height), area.zone, width, height) < (
        ZONE_BORDER * max(width, height)
    ):
        return "ROOM"
    if share >= DOOR_SHARE:
        return "ROOM"
    return "MIDDLE"


def _end(boxes: list[Box], area: Area, width: int, height: int) -> Place:
    places = {place(box, area, width, height) for box in boxes}
    if "ROOM" in places:
        return "ROOM"
    return "DOOR" if "DOOR" in places else "MIDDLE"


def _size_check(walk: Walk) -> Direction | None:
    start = statistics.median(_size(box) for _, box in walk.first)
    end = statistics.median(_size(box) for _, box in walk.last)
    if start <= 0:
        return None
    ratio = end / start
    if ratio >= GROWS:
        return "IN"
    if ratio <= 1 / GROWS:
        return "OUT"
    return None


def _side_check(walk: Walk, faces: FacesVisibleWhen) -> Direction | None:
    if walk.faces_found >= 2 and walk.faces_found >= FRONT_SHARE * walk.face_checks:
        towards = True
    elif walk.face_checks >= BACK_CHECKS and walk.faces_found == 0:
        towards = False
    else:
        return None
    entering = towards == (faces == "ENTERING")
    return "IN" if entering else "OUT"


def _size(box: Box) -> float:
    x1, y1, x2, y2 = box
    return math.sqrt(max(0.0, x2 - x1) * max(0.0, y2 - y1))


def _door_share(box: Box, door: tuple[Point, ...], width: int, height: int, steps: int = 9) -> float:
    """The share of the box inside the door area, on a grid of sample points."""
    x1, y1, x2, y2 = box
    inside = 0
    for i in range(steps):
        x = (x1 + (x2 - x1) * (i + 0.5) / steps) / width
        for j in range(steps):
            y = (y1 + (y2 - y1) * (j + 0.5) / steps) / height
            inside += zone.contains(door, (x, y))
    return inside / (steps * steps)


def _door_width(door: tuple[Point, ...], width: int) -> float:
    xs = [x for x, _ in door]
    return (max(xs) - min(xs)) * width


def _border_distance(point: Point, polygon: tuple[Point, ...], width: int, height: int) -> float:
    """Pixels from a normalised point to the nearest edge of a normalised polygon."""
    px, py = point[0] * width, point[1] * height
    best = math.inf
    for (nax, nay), (nbx, nby) in zip(polygon, polygon[1:] + polygon[:1], strict=True):
        ax, ay, bx, by = nax * width, nay * height, nbx * width, nby * height
        dx, dy = bx - ax, by - ay
        length = dx * dx + dy * dy
        t = 0.0 if length == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length))
        best = min(best, math.hypot(px - (ax + t * dx), py - (ay + t * dy)))
    return best

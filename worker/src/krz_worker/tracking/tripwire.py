"""Tripwire maths (pure): which side of the line a track's anchor is on, when a side change is a confirmed crossing,
and the U-turn hold.

The wire runs from ``a`` to ``b`` in normalised image coordinates (y down). The positive side is where the normal
(-(b.y - a.y), b.x - a.x) points; ``in_side`` (+1 or -1) says which side is inside, as the prototype draws the arrow
("inside is towards the camera" for a horizontal wire with in_side +1).
"""

from __future__ import annotations

import datetime as dt
import math
from dataclasses import dataclass, field
from typing import Generic, Literal, TypeVar

Direction = Literal["IN", "OUT"]
Point = tuple[float, float]

#: Half-width of the band around the line where no side is decided, as a fraction of the frame height.
HYSTERESIS = 0.015


@dataclass(frozen=True)
class Wire:
    a: Point
    b: Point
    in_side: int

    def signed_distance(self, anchor_px: Point, width: int, height: int) -> float:
        """Distance in pixels from the line, positive on the normal's side."""
        ax, ay = self.a[0] * width, self.a[1] * height
        bx, by = self.b[0] * width, self.b[1] * height
        px, py = anchor_px
        length = math.hypot(bx - ax, by - ay)
        if length == 0:
            return 0.0
        return ((bx - ax) * (py - ay) - (by - ay) * (px - ax)) / length


@dataclass(frozen=True)
class Crossing:
    direction: Direction
    #: When the anchor first stood on the new side (the frame that started the confirmation).
    at: dt.datetime
    #: Consecutive frames on the new side that confirmed it.
    frames: int


@dataclass
class LineState:
    """One track's position relative to the wire. A side change counts once it holds for ``confirm_frames`` frames;
    frames inside the hysteresis band neither confirm nor cancel."""

    stable_side: int | None = None
    candidate_side: int | None = None
    candidate_frames: int = 0
    candidate_since: dt.datetime | None = None

    def update(
        self, wire: Wire, anchor_px: Point, width: int, height: int, confirm_frames: int, ts: dt.datetime
    ) -> Crossing | None:
        distance = wire.signed_distance(anchor_px, width, height)
        if abs(distance) <= HYSTERESIS * height:
            return None
        side = 1 if distance > 0 else -1
        if self.stable_side is None:
            self.stable_side = side
            return None
        if side == self.stable_side:
            self.candidate_side, self.candidate_frames, self.candidate_since = None, 0, None
            return None
        if side != self.candidate_side:
            self.candidate_side, self.candidate_frames, self.candidate_since = side, 0, ts
        self.candidate_frames += 1
        if self.candidate_frames < confirm_frames:
            return None
        crossing = Crossing(
            direction="IN" if side == wire.in_side else "OUT",
            at=self.candidate_since or ts,
            frames=self.candidate_frames,
        )
        self.stable_side = side
        self.candidate_side, self.candidate_frames, self.candidate_since = None, 0, None
        return crossing


T = TypeVar("T")


@dataclass(frozen=True)
class Released(Generic[T]):
    item: T
    u_turn: bool


@dataclass
class _Held(Generic[T]):
    track_id: int
    direction: Direction
    item: T
    until: dt.datetime


@dataclass
class UturnHold(Generic[T]):
    """A stream message cannot change after XADD, so each confirmed crossing waits ``window`` before it is released.
    If the same track crosses back meanwhile, both are released as U-turns; otherwise the crossing is released alone.
    """

    window: dt.timedelta
    _held: list[_Held[T]] = field(default_factory=list)

    def confirm(self, track_id: int, direction: Direction, item: T, now: dt.datetime) -> list[Released[T]]:
        for held in self._held:
            if held.track_id == track_id and held.direction != direction and now <= held.until:
                self._held.remove(held)
                return [Released(held.item, u_turn=True), Released(item, u_turn=True)]
        if self.window <= dt.timedelta(0):
            return [Released(item, u_turn=False)]
        self._held.append(_Held(track_id, direction, item, now + self.window))
        return []

    def due(self, now: dt.datetime) -> list[Released[T]]:
        ready = [held for held in self._held if now > held.until]
        self._held = [held for held in self._held if now <= held.until]
        return [Released(held.item, u_turn=False) for held in ready]

    def flush(self) -> list[Released[T]]:
        """Everything still held, released as it stands (shutdown)."""
        ready, self._held = self._held, []
        return [Released(held.item, u_turn=False) for held in ready]

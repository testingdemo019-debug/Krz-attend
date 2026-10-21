"""WORKER_MAX_FPS without sleeping: which pictures of a live stream to analyse.

A picture is analysed when a whole credit has built up; credits build at ``rate`` per second, counted on the
pictures' arrival times, up to ``BURST``. The loop itself only waits for the reader's signal that a new picture
arrived. A sleep between pictures would add the timer's error (Windows sleeps in steps of about 15 ms): with the limit
at the camera's rate every overshoot skipped a picture, so a 25 fps camera was analysed at about 21. Now a limit at or
above the camera's rate analyses every picture, and a lower limit skips the ones that came too soon. The credit store
absorbs the uneven arrival of a network stream (and a coarse clock); over any stretch at most ``rate`` pictures a second
are analysed, plus ``BURST``.
"""

from __future__ import annotations

#: The most credits that can build up, and the credits at the start: room for pictures that arrive closer together than
#: 1/rate after a late one (a network stream's arrival varies around its average).
BURST = 4.0
#: Rounding room: 25 pictures 40 ms apart add up to 0.99999… of a credit each.
EPSILON = 1e-6


class FrameBudget:
    def __init__(self, rate: float) -> None:
        self._rate = rate
        self._credits = BURST
        self._last: float | None = None

    def take(self, arrived: float) -> bool:
        """Whether to analyse the picture that arrived at ``arrived`` (seconds, monotonic)."""
        if self._last is not None:
            self._credits = min(BURST, self._credits + max(0.0, arrived - self._last) * self._rate)
        self._last = arrived
        if self._credits >= 1.0 - EPSILON:
            self._credits -= 1.0
            return True
        return False

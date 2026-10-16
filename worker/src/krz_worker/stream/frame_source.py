"""Where frames come from: the camera (RTSP) or a recorded clip (tests and replay_video.py).

``RtspSource`` decodes on its own thread and keeps only the newest frame, stamped with its arrival time, so processing
never lags behind the camera. After an error, or 5 s without data (PyAV's read timeout), it reconnects with back-off
and reports ``connecting`` / ``offline``; a connection that delivered frames starts the back-off again.
``FileSource`` returns every frame of a file in order, with times derived from the frame rate, so a replay is
deterministic.
"""

from __future__ import annotations

import datetime as dt
import logging
import threading
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal

import av

from krz_worker.models.base import Image
from krz_worker.stream.rtsp import mask, open_stream
from krz_worker.timeutil import now_utc

LOG = logging.getLogger(__name__)

Status = Literal["live", "connecting", "offline"]
BACKOFF_SECONDS = (1, 2, 5, 10, 30)


@dataclass(frozen=True)
class Frame:
    image: Image
    ts: dt.datetime
    #: Monotonic arrival time (latency measurement).
    arrived: float


@dataclass
class StreamInfo:
    status: Status = "connecting"
    codec: str | None = None
    resolution: str | None = None
    reconnects: int = 0
    error_code: str | None = None
    last_frame_ts: dt.datetime | None = None
    decoded_fps: float | None = None
    _times: list[float] = field(default_factory=list)


class FrameSource(ABC):
    info: StreamInfo

    @abstractmethod
    def start(self) -> None: ...

    @abstractmethod
    def read(self, timeout: float) -> Frame | None:
        """The next frame to process, or None when none came within the timeout (or the source ended)."""

    @abstractmethod
    def stop(self) -> None: ...

    @property
    def finished(self) -> bool:
        return False


class RtspSource(FrameSource):
    def __init__(self, url: str, camera: str) -> None:
        self._url = url
        self._camera = camera
        self.info = StreamInfo()
        self._latest: Frame | None = None
        self._given: Frame | None = None
        self._condition = threading.Condition()
        self._stop = threading.Event()
        self._session_frames = 0
        self._thread = threading.Thread(target=self._run, name=f"rtsp-{camera}", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=7)

    def read(self, timeout: float) -> Frame | None:
        with self._condition:
            if self._latest is self._given:
                self._condition.wait(timeout)
            if self._latest is None or self._latest is self._given:
                return None
            self._given = self._latest
            return self._latest

    def _run(self) -> None:
        attempt = 0
        while not self._stop.is_set():
            self._session_frames = 0
            try:
                self._read_until_failure()
            except Exception as error:  # noqa: BLE001 - every stream failure means reconnect
                self.info.error_code = error_code(error)
                LOG.warning(
                    "Camera stream failed: %s",
                    mask(f"{type(error).__name__}: {error}"),
                    extra={"camera": self._camera, "error_code": self.info.error_code},
                )
            if self._stop.is_set():
                break
            # After hours of video a hiccup retries at once, not after the longest wait of earlier failures.
            if self._session_frames:
                attempt = 0
            self.info.status = "offline" if attempt >= 2 else "connecting"
            self.info.reconnects += 1
            self._stop.wait(BACKOFF_SECONDS[min(attempt, len(BACKOFF_SECONDS) - 1)])
            attempt += 1

    def _read_until_failure(self) -> None:
        container: Any = open_stream(self._url)
        try:
            video = container.streams.video[0]
            video.thread_type = "AUTO"
            self.info.codec = video.codec_context.name
            for decoded in container.decode(video):
                if self._stop.is_set():
                    return
                image = decoded.to_ndarray(format="bgr24")
                frame = Frame(image, now_utc(), time.monotonic())
                self._note(frame)
                with self._condition:
                    self._latest = frame
                    self._condition.notify_all()
            raise ConnectionError("stream ended")
        finally:
            container.close()

    def _note(self, frame: Frame) -> None:
        info = self.info
        info.status = "live"
        info.error_code = None
        info.last_frame_ts = frame.ts
        info.resolution = f"{frame.image.shape[1]}x{frame.image.shape[0]}"
        info._times = [t for t in info._times if frame.arrived - t < 5.0] + [frame.arrived]
        span = info._times[-1] - info._times[0]
        # Frames decoded in one burst can share a clock tick (about 16 ms on Windows).
        if len(info._times) > 1 and span > 0:
            info.decoded_fps = round((len(info._times) - 1) / span, 1)
        self._session_frames += 1


class FileSource(FrameSource):
    """Every frame of a clip, in order; times start at ``start`` and follow the clip's frame rate."""

    def __init__(self, path: Path, start: dt.datetime | None = None, every: int = 1) -> None:
        self._container: Any = av.open(str(path))
        stream = self._container.streams.video[0]
        rate = float(stream.average_rate or 25)
        self._step = dt.timedelta(seconds=every / rate)
        self._every = every
        self._frames = self._container.decode(stream)
        self._start = start or now_utc()
        self._index = 0
        self._done = False
        self.info = StreamInfo(status="live", codec=stream.codec_context.name)
        self.fps = rate / every

    def start(self) -> None:
        return None

    def read(self, timeout: float) -> Frame | None:
        try:
            for _ in range(self._every - 1):
                next(self._frames)
            decoded = next(self._frames)
        except StopIteration:
            self._done = True
            return None
        image = decoded.to_ndarray(format="bgr24")
        frame = Frame(image, self._start + self._index * self._step, time.monotonic())
        self._index += 1
        self.info.resolution = f"{image.shape[1]}x{image.shape[0]}"
        self.info.last_frame_ts = frame.ts
        return frame

    def stop(self) -> None:
        self._container.close()

    @property
    def finished(self) -> bool:
        return self._done


def error_code(error: Exception) -> str:
    """The camera-test error codes: AUTH_FAILED, TIMEOUT, UNREACHABLE or UNSUPPORTED_CODEC."""
    text = f"{type(error).__name__} {error}".lower()
    if "401" in text or "unauthorized" in text or "403" in text or "forbidden" in text:
        return "AUTH_FAILED"
    if "timed out" in text or "timeout" in text or "etimedout" in text:
        return "TIMEOUT"
    if "decoder" in text or "codec" in text or "invalid data" in text or "indexerror" in text:
        return "UNSUPPORTED_CODEC"
    return "UNREACHABLE"

"""What the camera pipelines and the internal API share at run time. The config watcher replaces the live parts
(thresholds, labels, cameras, gallery) atomically; a reader always sees a whole old or a whole new value."""

from __future__ import annotations

import datetime as dt
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, TypeVar

import redis

from krz_worker.config import Settings
from krz_worker.crypto import KeyRing
from krz_worker.pipeline.calibration import Calibrations
from krz_worker.pipeline.enrol_capture import Captures
from krz_worker.recognition.decision import Thresholds
from krz_worker.recognition.gallery import Gallery
from krz_worker.timeutil import now_utc

if TYPE_CHECKING:
    from krz_worker.db import CameraConfig
    from krz_worker.models.registry import Models
    from krz_worker.recognition.reid_gallery import ReIdGallery
    from krz_worker.recognition.unknown_clusters import UnknownClusters

LOG = logging.getLogger(__name__)
T = TypeVar("T")


@dataclass
class Live:
    """Configuration from the database, swapped as a whole by the config watcher."""

    thresholds: Thresholds | None = None
    labels: dict[str, str] = field(default_factory=dict)
    cameras: dict[str, CameraConfig] = field(default_factory=dict)


@dataclass
class Context:
    settings: Settings
    ring: KeyRing
    gallery: Gallery = field(default_factory=Gallery)
    live: Live = field(default_factory=Live)
    calibrations: Calibrations = field(default_factory=Calibrations)
    #: Enrolment captures at entry points (D-118).
    captures: Captures = field(default_factory=Captures)
    models: Models | None = None
    reid: ReIdGallery | None = None
    clusters: UnknownClusters | None = None
    #: The camera pipelines by code, for /health and the snapshot route.
    pipelines: dict[str, Any] = field(default_factory=dict)
    started_at: dt.datetime = field(default_factory=now_utc)
    #: Models loaded and the first configuration read: crossings can be produced.
    ready: bool = False


def best_effort(what: str, call: Callable[[], T], default: T) -> T:
    """Redis side-tasks (Re-ID gallery, unknown clusters) must never stop a crossing: on failure, the default."""
    try:
        return call()
    except redis.RedisError as error:
        LOG.warning("%s skipped: Redis unavailable (%s)", what, type(error).__name__)
        return default

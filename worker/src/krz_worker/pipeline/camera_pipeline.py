"""One camera: frames in, crossings, overlays and heartbeats out (P3).

Per processed frame: people (YOLOX) whose anchor is inside the zone -> ByteTrack IDs -> per track the tripwire,
face checks (YuNet on the head region at full resolution, quality gate, liveness, SFace, gallery vote) and body
embeddings (OSNet) -> a confirmed crossing gets its R14 decision, crops and message, then waits out the U-turn window
before it is sent. Direction modes are not applied here: every crossing is sent and the backend applies the camera's
mode, so the rule lives in one place. During a calibration run crossings go to the run instead of the stream.

``process`` does the work of one frame and returns what it emitted, so tests and replay_video.py drive it directly;
``run`` paces it at WORKER_MAX_FPS on a live source, writes the heartbeat and survives per-frame errors (30 in a row
end the pipeline, and the supervisor restarts it).
"""

from __future__ import annotations

import datetime as dt
import logging
import statistics
import threading
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, replace
from typing import Any

import numpy as np

from krz_worker.context import Context, best_effort
from krz_worker.db import CameraConfig
from krz_worker.models.base import Image, Person
from krz_worker.pipeline import crops as crop_files
from krz_worker.pipeline.calibration import CalibrationCrossing, Run
from krz_worker.pipeline.crossing_builder import build, new_crossing_uid, new_trace_id
from krz_worker.pipeline.overlays import OverlayTrack, Throttle, payload
from krz_worker.recognition import quality as face_quality
from krz_worker.recognition.decision import Evidence, Thresholds, Vote, decide, needs_reid
from krz_worker.stream import onvif
from krz_worker.stream.frame_source import Frame, FrameSource
from krz_worker.timeutil import iso_ms, ist_day, now_utc
from krz_worker.tracking import zone
from krz_worker.tracking.track_state import BodySample, FaceSample, TrackState
from krz_worker.tracking.tracker import PersonTracker
from krz_worker.tracking.tripwire import Crossing, Released, UturnHold

LOG = logging.getLogger(__name__)

HEAD_SHARE = 0.4
MIN_BODY_HEIGHT = 160
BODY_EVERY = 5
FACE_EVERY_DECIDED = 3
TRACK_TIMEOUT = dt.timedelta(seconds=3)
MAX_ERRORS = 30
CAMERA_HEARTBEAT_EVERY = 3.0
CLOCK_EVERY = 600.0
EDGE = 2


class PipelineError(RuntimeError):
    """Too many consecutive frame errors: the supervisor restarts the pipeline."""


@dataclass(frozen=True)
class _Pending:
    """A confirmed crossing waiting out the U-turn window: how to build its message, or its calibration entry.

    The message is built when the crossing is released, not when it is confirmed: the face and body samples the
    track gathers during the window count too. Walking towards the camera, the face is largest just after the line.
    """

    message: Callable[[], dict[str, str]] | None
    calibration: tuple[Run, CalibrationCrossing] | None


class CameraPipeline:
    def __init__(
        self,
        camera: CameraConfig,
        source: FrameSource,
        context: Context,
        emit: Callable[[dict[str, str]], None],
        publish_overlay: Callable[[str, dict[str, Any]], None] | None = None,
        heartbeat: Callable[[str, dict[str, Any], int], None] | None = None,
    ) -> None:
        if context.models is None:
            raise RuntimeError("models are not loaded")
        self._ctx = context
        self._models = context.models
        self.camera = camera
        self.source = source
        self._emit = emit
        self._publish_overlay = publish_overlay
        self._heartbeat = heartbeat
        self._tracker = PersonTracker(context.settings.max_fps)
        self._tracks: dict[int, TrackState] = {}
        self._hold: UturnHold[_Pending] = UturnHold(dt.timedelta(seconds=camera.ignore_uturn_sec))
        self._frame_index = 0
        self._overlay = Throttle()
        self._latency_ms: deque[float] = deque(maxlen=50)
        self._process_ms: deque[tuple[float, float]] = deque(maxlen=2000)
        self._processed: deque[float] = deque(maxlen=200)
        self._clock_offset: float | None = None
        self.latest: Frame | None = None

    # --- configuration ------------------------------------------------------------------------------------------

    def apply(self, camera: CameraConfig) -> None:
        """Geometry, liveness and the U-turn window apply from the next frame (a connection change restarts the
        pipeline instead)."""
        if camera.ignore_uturn_sec != self.camera.ignore_uturn_sec:
            self._hold.window = dt.timedelta(seconds=camera.ignore_uturn_sec)
        self.camera = camera

    # --- one frame ----------------------------------------------------------------------------------------------

    def process(self, frame: Frame) -> list[dict[str, str]]:
        started = time.perf_counter()
        thresholds = self._ctx.live.thresholds
        if thresholds is None:
            raise RuntimeError("no threshold profile loaded")
        self.latest = frame
        self._frame_index += 1
        image = frame.image
        height, width = image.shape[:2]
        people = [p for p in self._models.person_detector.detect(image) if self._in_zone(p, width, height)]
        emitted: list[dict[str, str]] = []
        for track_id, person in self._tracker.update(people):
            state = self._tracks.get(track_id)
            if state is None:
                state = self._tracks[track_id] = TrackState(track_id, frame.ts, frame.ts)
            state.last_seen, state.box = frame.ts, person.box
            self._faces(state, image, thresholds)
            self._body(state, image)
            crossing = self._tripwire(state, width, height, frame.ts)
            if crossing is not None:
                emitted += self._confirm(state, person, crossing, frame, thresholds)
        emitted += [self._release(r) for r in self._hold.due(frame.ts)]
        self._tracks = {k: s for k, s in self._tracks.items() if frame.ts - s.last_seen <= TRACK_TIMEOUT}
        self._overlays(width, height, thresholds)
        elapsed = (time.perf_counter() - started) * 1000
        self._process_ms.append((time.monotonic(), elapsed))
        self._latency_ms.append((time.monotonic() - frame.arrived) * 1000)
        self._processed.append(time.monotonic())
        return [e for e in emitted if e]

    def flush(self) -> list[dict[str, str]]:
        """Shutdown: every held crossing is sent as it stands."""
        return [e for e in (self._release(r) for r in self._hold.flush()) if e]

    def _in_zone(self, person: Person, width: int, height: int) -> bool:
        return zone.contains(self.camera.zone, zone.anchor(person.box, width, height))

    def _tripwire(self, state: TrackState, width: int, height: int, ts: dt.datetime) -> Crossing | None:
        if self.camera.wire is None:
            return None
        x1, _, x2, y2 = state.box
        return state.line.update(self.camera.wire, ((x1 + x2) / 2, y2), width, height, self.camera.confirm_frames, ts)

    def _faces(self, state: TrackState, image: Image, thresholds: Thresholds) -> None:
        decided = (
            len(state.votes) >= thresholds.agree_k
            and decide(state.evidence("IN", self.camera.liveness_enabled), thresholds).hint == "AUTO"
        )
        x1, y1, x2, y2 = state.box
        head_bottom = y1 + (y2 - y1) * HEAD_SHARE
        grown = state.last_face_height > 0 and (y2 - y1) > state.last_face_height * (1 + thresholds.recheck_grow / 100)
        if decided and not grown and self._frame_index - state.last_face_check < FACE_EVERY_DECIDED:
            return
        state.last_face_check = self._frame_index
        head = crop_files.body_crop(image, (x1, y1, x2, head_bottom))
        if head.size == 0:
            return
        found = self._models.face_detector.detect(head)
        if not found:
            return
        face = max(found, key=lambda f: f.box[2] * f.box[3]).shifted(max(0.0, x1), max(0.0, y1))
        state.last_face, state.last_face_height = face, y2 - y1
        aligned = self._models.face_embedder.align(image, face)
        measured = face_quality.measure(face, aligned, thresholds.gate)
        embedding = None
        if measured.passed:
            if self.camera.liveness_enabled:
                state.liveness.append(self._models.liveness.check(image, face))
            embedding = self._models.face_embedder.embed([aligned])[0]
            state.votes.append(Vote(tuple(self._ctx.gallery.current().match(embedding[None, :], k=3))))
        state.offer_face(FaceSample(measured, measured.passed, crop_files.face_crop(image, face.box), embedding))

    def _body(self, state: TrackState, image: Image) -> None:
        if self._frame_index - state.last_body_check < BODY_EVERY:
            return
        height, width = image.shape[:2]
        x1, y1, x2, y2 = state.box
        box_height = y2 - y1
        whole = x1 > EDGE and y1 > EDGE and x2 < width - EDGE and y2 < height - EDGE
        if box_height < MIN_BODY_HEIGHT or not whole:
            return
        state.last_body_check = self._frame_index
        crop = crop_files.body_crop(image, state.box)
        embedding = self._models.reid.embed([crop])[0]
        face_in_crop = None
        if state.last_face is not None:
            fx, fy, fw, fh = state.last_face.box
            face_in_crop = (fx - x1, fy - y1, fw, fh)
        state.offer_body(box_height, embedding, BodySample(box_height, crop, face_in_crop))

    # --- crossings ----------------------------------------------------------------------------------------------

    def _confirm(
        self, state: TrackState, person: Person, crossing: Crossing, frame: Frame, thresholds: Thresholds
    ) -> list[dict[str, str]]:
        state.direction = crossing.direction
        run = self._ctx.calibrations.active(self.camera.code, now_utc())
        if run is not None:
            entry = CalibrationCrossing(crossing.at, crossing.direction, person.score, crossing.frames, False)
            pending = _Pending(None, (run, entry))
        else:
            pending = _Pending(lambda: self._message(state, crossing, thresholds), None)
        return [self._release(r) for r in self._hold.confirm(state.track_id, crossing.direction, pending, frame.ts)]

    def _message(self, state: TrackState, crossing: Crossing, thresholds: Thresholds) -> dict[str, str]:
        ctx = self._ctx
        evidence = state.evidence(crossing.direction, self.camera.liveness_enabled)
        bodies = (
            np.stack([embedding for _, embedding in state.bodies])
            if state.bodies
            else np.zeros((0, 512), dtype=np.float32)
        )
        if needs_reid(evidence, thresholds) and ctx.reid is not None:
            reid = ctx.reid
            evidence = Evidence(
                evidence.direction,
                evidence.votes,
                evidence.liveness,
                evidence.liveness_on,
                tuple(best_effort("Re-ID match", lambda: reid.match(bodies), [])),
            )
        decision = decide(evidence, thresholds)
        crossing_uid, trace_id = new_crossing_uid(), new_trace_id()
        cluster = None
        best_face = state.best_face
        if decision.hint == "UNKNOWN" and best_face is not None and best_face.embedding is not None:
            clusters, embedding = ctx.clusters, best_face.embedding
            if clusters is not None:
                cluster = best_effort(
                    "Unknown clustering", lambda: clusters.assign(ist_day(crossing.at), embedding), None
                )
            if cluster is not None:
                state.unknown_label = "Unknown #" + cluster.split(":")[1]
        if ctx.reid is not None:
            reid = ctx.reid
            if decision.hint == "AUTO" and crossing.direction == "IN" and decision.employee_id is not None:
                employee = decision.employee_id
                best_effort("Re-ID gallery", lambda: reid.add(employee, bodies), None)
            elif decision.hint != "AUTO":
                best_effort("Re-ID hold", lambda: reid.hold(crossing_uid, bodies), None)
        try:
            paths = crop_files.write(
                ctx.ring,
                ctx.settings.crops_dir,
                crossing_uid,
                crossing.at,
                best_face.crop if best_face is not None else None,
                state.best_body.crop if state.best_body is not None else None,
                state.best_body.face_in_crop if state.best_body is not None else None,
            )
        except OSError:
            LOG.exception("Crops not written", extra={"camera": self.camera.code, "crossing_uid": crossing_uid})
            paths = crop_files.CropPaths()
        return build(
            crossing_uid=crossing_uid,
            camera=self.camera.code,
            at=crossing.at,
            direction=crossing.direction,
            u_turn=False,
            decision=decision,
            quality=best_face.quality if best_face is not None else None,
            unknown_cluster=cluster,
            crops=paths,
            track_id=state.track_id,
            model_version=self._models.model_version,
            worker_id=ctx.settings.worker_id,
            trace_id=trace_id,
        )

    def _release(self, released: Released[_Pending]) -> dict[str, str]:
        pending = released.item
        if pending.calibration is not None:
            run, entry = pending.calibration
            self._ctx.calibrations.record(run, replace(entry, u_turn=released.u_turn))
            return {}
        if pending.message is None:
            raise RuntimeError("a held crossing has neither a message nor a calibration entry")
        fields = {**pending.message(), "u_turn": "true" if released.u_turn else "false"}
        self._emit(fields)
        LOG.info(
            "Crossing %s %s%s",
            fields["direction"],
            fields["decision_hint"],
            " (U-turn)" if released.u_turn else "",
            extra={
                "camera": self.camera.code,
                "crossing_uid": fields["crossing_uid"],
                "trace_id": fields["trace_id"],
                "decision": fields["decision_hint"],
                "employee_id": fields["employee_id"] or None,
                "track_id": fields["track_id"],
            },
        )
        return fields

    # --- overlay and heartbeat ------------------------------------------------------------------------------------

    def _overlays(self, width: int, height: int, thresholds: Thresholds) -> None:
        if self._publish_overlay is None or not self._overlay.ready():
            return
        names = self._ctx.live.labels
        tracks = []
        for state in self._tracks.values():
            label, status, band = state.overlay(thresholds, self.camera.liveness_enabled, names)
            badge = state.best_face.quality.badge(thresholds.quality_min) if state.best_face is not None else None
            tracks.append(OverlayTrack(state.track_id, state.box, label, status, state.direction, band, badge))
        self._publish_overlay(self.camera.code, payload(self.camera.code, width, height, tracks, self.fps))

    @property
    def fps(self) -> float | None:
        if len(self._processed) < 2:
            return None
        recent = [t for t in self._processed if self._processed[-1] - t <= 5.0]
        span = recent[-1] - recent[0]
        return round((len(recent) - 1) / span, 1) if span > 0 else None

    def heartbeat_payload(self) -> dict[str, Any]:
        info = self.source.info
        minute = [ms for at, ms in self._process_ms if time.monotonic() - at <= 60]
        payload: dict[str, Any] = {
            "status": info.status,
            "fps": self.fps,
            "latency_ms": round(statistics.fmean(self._latency_ms)) if self._latency_ms else None,
            "resolution": info.resolution,
            "codec": info.codec,
            "last_frame_ts": iso_ms(info.last_frame_ts) if info.last_frame_ts else None,
            "reconnects": info.reconnects,
            "clock_offset_s": self._clock_offset,
        }
        if info.error_code:
            payload["error_code"] = info.error_code
        if len(minute) >= 2:
            quantiles = statistics.quantiles(minute, n=20)
            payload["process_ms"] = {"p50": round(statistics.median(minute)), "p95": round(quantiles[18])}
        return payload

    # --- live loop ----------------------------------------------------------------------------------------------

    def run(self, stop: threading.Event) -> None:
        interval = 1.0 / self._ctx.settings.max_fps
        errors = 0
        next_heartbeat = 0.0
        self._start_clock_watch(stop)
        self.source.start()
        try:
            while not stop.is_set():
                started = time.monotonic()
                frame = self.source.read(timeout=1.0)
                if frame is not None:
                    try:
                        self.process(frame)
                        errors = 0
                    except Exception:
                        errors += 1
                        LOG.exception("Frame failed (%d in a row)", errors, extra={"camera": self.camera.code})
                        if errors >= MAX_ERRORS:
                            raise PipelineError(f"{MAX_ERRORS} frames failed in a row") from None
                if self._heartbeat is not None and time.monotonic() >= next_heartbeat:
                    self._heartbeat(self.camera.code, self.heartbeat_payload(), 10)
                    next_heartbeat = time.monotonic() + CAMERA_HEARTBEAT_EVERY
                stop.wait(max(0.0, interval - (time.monotonic() - started)))
        finally:
            self.flush()
            self.source.stop()

    def _start_clock_watch(self, stop: threading.Event) -> None:
        def watch() -> None:
            while not stop.is_set():
                self._clock_offset = onvif.clock_offset(self.camera.host, self.camera.onvif_port)
                stop.wait(CLOCK_EVERY)

        threading.Thread(target=watch, name=f"clock-{self.camera.code}", daemon=True).start()

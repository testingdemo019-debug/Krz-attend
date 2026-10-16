"""The worker process: ``python -m krz_worker [--host-dev]``.

Start-up: the internal API (it answers ``starting`` until ready), the models (SHA-256 verified; a mismatch stops the
process), the configuration from the database views (retried with back-off), then one supervised pipeline per enabled
camera, the ``krz:config`` listener with the 30 s version poll, the worker heartbeat and the spool replayer.

A pipeline that fails is restarted after 1, 2, 5, 10, then every 30 s; one camera never stops the others. On SIGTERM
the readers stop, held crossings are sent, the spool is flushed if Redis is back, Redis and the database are closed,
and the process exits within 15 s.
"""

from __future__ import annotations

import argparse
import logging
import signal
import sys
import threading
import time
from types import FrameType
from typing import Any

import cv2
import uvicorn

from krz_worker import config, gpu, logging_setup
from krz_worker.api import app as api_app
from krz_worker.context import Context, best_effort
from krz_worker.crypto import KeyRing
from krz_worker.db import CameraConfig, ConfigVersion, Database
from krz_worker.models import registry
from krz_worker.pipeline.camera_pipeline import CameraPipeline
from krz_worker.recognition import gallery
from krz_worker.recognition.reid_gallery import ReIdGallery
from krz_worker.recognition.unknown_clusters import UnknownClusters
from krz_worker.redis_bus import CAMERA_HEARTBEAT, WORKER_HEARTBEAT, RedisBus
from krz_worker.stream.frame_source import RtspSource
from krz_worker.stream.rtsp import build_url
from krz_worker.timeutil import iso_ms

LOG = logging.getLogger("krz_worker")

BACKOFF_SECONDS = (1, 2, 5, 10, 30)
#: A pipeline that ran this long counts as healthy again: the next failure starts the back-off from 1 s.
HEALTHY_AFTER = 60.0
POLL_SECONDS = 30.0
HEARTBEAT_SECONDS = 5.0
SPOOL_SECONDS = 2.0


class CameraRunner:
    """Keeps one camera's pipeline running, restarting it with back-off."""

    def __init__(self, supervisor: Supervisor, camera: CameraConfig) -> None:
        self.camera = camera
        self._supervisor = supervisor
        self._stop = threading.Event()
        self._pipeline: CameraPipeline | None = None
        self._thread = threading.Thread(target=self._run, name=f"camera-{camera.code}", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self, timeout: float) -> None:
        self._stop.set()
        self._thread.join(timeout)

    def apply(self, camera: CameraConfig) -> None:
        self.camera = camera
        if self._pipeline is not None:
            self._pipeline.apply(camera)

    def _run(self) -> None:
        attempt = 0
        while not self._stop.is_set():
            started = time.monotonic()
            try:
                self._pipeline = self._supervisor.pipeline(self.camera)
                self._pipeline.run(self._stop)
            except Exception:
                LOG.exception("Camera pipeline stopped", extra={"camera": self.camera.code})
            if self._stop.is_set():
                break
            if time.monotonic() - started >= HEALTHY_AFTER:
                attempt = 0
            self._stop.wait(BACKOFF_SECONDS[min(attempt, len(BACKOFF_SECONDS) - 1)])
            attempt += 1


class Supervisor:
    def __init__(self, context: Context) -> None:
        self.ctx = context
        settings = context.settings
        self.stopping = threading.Event()
        self.db = Database(settings)
        self.bus = RedisBus(settings)
        self._runners: dict[str, CameraRunner] = {}
        self._runners_lock = threading.Lock()
        self._version: ConfigVersion | None = None
        self._api = uvicorn.Server(
            uvicorn.Config(
                api_app.create(context),
                host="0.0.0.0",  # noqa: S104 - the internal network; dev publishes 127.0.0.1 only
                port=settings.api_port,
                log_config=None,
                access_log=False,
            )
        )

    def run(self) -> int:
        api_thread = threading.Thread(target=self._api.run, name="api", daemon=True)
        api_thread.start()
        try:
            self.ctx.models = registry.load(self.ctx.settings)
        except (registry.ModelIntegrityError, OSError, ValueError) as error:
            LOG.critical("Models cannot be used: %s", error)
            self._api.should_exit = True
            return 1
        self.ctx.reid = ReIdGallery(self.bus.client)
        self.ctx.clusters = UnknownClusters(self.bus.client)
        self.db.open()
        self._load_until_ready()
        if self.stopping.is_set():
            return self._shutdown(api_thread)
        self.ctx.ready = True
        self.bus.listen_config(self._on_signal)
        for name, every, task in (
            ("config-poll", POLL_SECONDS, self._poll),
            ("heartbeat", HEARTBEAT_SECONDS, self._heartbeat),
            ("spool", SPOOL_SECONDS, self.bus.replay_spool),
        ):
            threading.Thread(target=self._every, args=(every, task), name=name, daemon=True).start()
        self._sync_cameras()
        self.stopping.wait()
        return self._shutdown(api_thread)

    def stop(self) -> None:
        self.stopping.set()

    # --- configuration ------------------------------------------------------------------------------------------

    def _load_until_ready(self) -> None:
        attempt = 0
        while not self.stopping.is_set():
            try:
                self._reload_thresholds()
                self._reload_gallery()
                self._reload_cameras()
                self._version = self.db.version()
            except Exception as error:  # noqa: BLE001 - the database may come up after the worker
                LOG.warning("Configuration not readable yet: %s", type(error).__name__)
                self.stopping.wait(BACKOFF_SECONDS[min(attempt, len(BACKOFF_SECONDS) - 1)])
                attempt += 1
            else:
                return

    def _reload_thresholds(self) -> None:
        self.ctx.live.thresholds = self.db.thresholds()
        LOG.info("Threshold profile %s active", self.ctx.live.thresholds.code)

    def _reload_gallery(self) -> None:
        models = self.ctx.models
        if models is None:
            raise RuntimeError("models are not loaded")
        rows = self.db.gallery()
        snapshot = gallery.build(rows, self.ctx.ring, models.face_embedder.name, models.face_embedder.dim)
        self.ctx.gallery.swap(snapshot)
        self.ctx.live.labels = self.db.labels()

    def _reload_cameras(self) -> None:
        self.ctx.live.cameras = {c.code: c for c in self.db.cameras()}

    def _on_signal(self, signal_: dict[str, Any]) -> None:
        """A ``krz:config`` signal; the reload runs on its own thread so the listener keeps listening."""
        kind = signal_.get("type")
        reid = self.ctx.reid
        if kind in {"reid.bind", "reid.unbind"} and reid is not None:
            uid, employee = str(signal_.get("crossing_uid") or ""), str(signal_.get("employee_id") or "")
            if uid and employee:
                if kind == "reid.bind":
                    best_effort("Re-ID bind", lambda: reid.bind(uid, employee), None)
                else:
                    best_effort("Re-ID unbind", lambda: reid.unbind(uid, employee), None)
            return
        work = {"gallery": self._reload_gallery, "recognition": self._reload_thresholds, "camera": self._camera_change}
        task = work.get(str(kind))
        if task is not None:
            threading.Thread(target=self._safely, args=(task,), name=f"reload-{kind}", daemon=True).start()

    def _camera_change(self) -> None:
        self._reload_cameras()
        self._sync_cameras()

    def _poll(self) -> None:
        """The safety net under pub/sub: any change of the polled versions reloads that part."""
        version = self.db.version()
        previous, self._version = self._version, version
        if previous is None or version.gallery != previous.gallery:
            self._reload_gallery()
        else:
            self.ctx.live.labels = self.db.labels()
        if previous is None or version.recognition != previous.recognition:
            self._reload_thresholds()
        if previous is None or version.cameras != previous.cameras:
            self._camera_change()

    # --- cameras ------------------------------------------------------------------------------------------------

    def pipeline(self, camera: CameraConfig) -> CameraPipeline:
        password = self.ctx.ring.decrypt(camera.password_enc).decode("utf-8") if camera.password_enc else None
        url = build_url(camera.host, camera.rtsp_port, camera.main_path, camera.username, password)
        pipeline = CameraPipeline(
            camera,
            RtspSource(url, camera.code),
            self.ctx,
            emit=self.bus.send_crossing,
            publish_overlay=self.bus.publish_overlay,
            heartbeat=lambda code, payload, ttl: self.bus.heartbeat(CAMERA_HEARTBEAT + code, payload, ttl),
        )
        self.ctx.pipelines[camera.code] = pipeline
        return pipeline

    def _sync_cameras(self) -> None:
        wanted = {code: c for code, c in self.ctx.live.cameras.items() if c.enabled}
        with self._runners_lock:
            for code in list(self._runners):
                runner = self._runners[code]
                if code not in wanted or wanted[code].connection != runner.camera.connection:
                    runner.stop(timeout=10)
                    del self._runners[code]
                    self.ctx.pipelines.pop(code, None)
                    LOG.info("Camera pipeline stopped (disabled, removed or reconnecting)", extra={"camera": code})
            for code, camera in wanted.items():
                if code in self._runners:
                    self._runners[code].apply(camera)
                else:
                    runner = CameraRunner(self, camera)
                    self._runners[code] = runner
                    runner.start()
                    LOG.info("Camera pipeline started", extra={"camera": code})

    # --- background tasks ---------------------------------------------------------------------------------------

    def _heartbeat(self) -> None:
        models = self.ctx.models
        snapshot = self.ctx.gallery.current()
        self.bus.heartbeat(
            WORKER_HEARTBEAT + self.ctx.settings.worker_id,
            {
                "version": self.ctx.settings.app_version,
                "started_at": iso_ms(self.ctx.started_at),
                "provider": models.provider if models is not None else None,
                "gpu": gpu.info(self.ctx.settings.gpu_device),
                "models": [{"name": m.name, "version": m.version, "sha256": m.sha256} for m in models.files]
                if models is not None
                else [],
                "gallery": {
                    "templates": snapshot.templates,
                    "employees": len(snapshot.employee_ids),
                    "refused": snapshot.refused,
                },
            },
            15,
        )

    def _every(self, seconds: float, task: Any) -> None:
        while not self.stopping.wait(seconds):
            self._safely(task)

    @staticmethod
    def _safely(task: Any) -> None:
        try:
            task()
        except Exception as error:  # noqa: BLE001 - background work retries at its next turn
            LOG.warning("%s failed: %s", getattr(task, "__name__", "task"), type(error).__name__)

    def _shutdown(self, api_thread: threading.Thread) -> int:
        LOG.info("Stopping")
        with self._runners_lock:
            runners = list(self._runners.values())
            self._runners.clear()
        for runner in runners:
            runner.stop(timeout=8)
        self._safely(self.bus.replay_spool)
        self.bus.close()
        self.db.close()
        self._api.should_exit = True
        api_thread.join(timeout=3)
        return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="krz_worker", description="KRZ Attend vision worker")
    parser.add_argument(
        "--host-dev", action="store_true", help="run on a development machine: deploy/.env, localhost, data/"
    )
    args = parser.parse_args(argv)
    loaded = config.load(host_dev=args.host_dev)
    logging_setup.configure(loaded.settings.log_dir)
    cv2.utils.logging.setLogLevel(cv2.utils.logging.LOG_LEVEL_ERROR)
    context = Context(loaded.settings, KeyRing.from_env(loaded.env))
    supervisor = Supervisor(context)

    def on_signal(signum: int, _frame: FrameType | None) -> None:
        LOG.info("Signal %s received", signal.Signals(signum).name)
        supervisor.stop()

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)
    LOG.info("Worker %s starting (version %s)", loaded.settings.worker_id, loaded.settings.app_version)
    return supervisor.run()


if __name__ == "__main__":
    sys.exit(main())

"""Read-only database access as ``krz_worker``, which may only SELECT the ``v_worker_*`` views (V012).

A small pool (1-4 connections), a 5 s statement timeout and read-only transactions; the pool reconnects by itself and
callers retry with back-off. The camera password and the template embeddings stay encrypted here.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Literal

from psycopg.conninfo import make_conninfo
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from krz_worker.config import Settings
from krz_worker.recognition.decision import Thresholds
from krz_worker.tracking.doorway import FacesVisibleWhen
from krz_worker.tracking.tripwire import Wire

LOG = logging.getLogger(__name__)

CountingMethod = Literal["LINE_CROSSING", "DOORWAY_ZONE"]


@dataclass(frozen=True)
class CameraConfig:
    code: str
    name: str
    enabled: bool
    host: str
    rtsp_port: int
    onvif_port: int
    main_path: str
    username: str | None
    password_enc: bytes | None
    #: Normalised polygon; empty = the whole image.
    zone: tuple[tuple[float, float], ...]
    #: None only for a camera in MONITOR mode.
    wire: Wire | None
    confirm_frames: int
    ignore_uturn_sec: int
    liveness_enabled: bool
    config_version: int
    #: LINE_CROSSING (the tripwire) or DOORWAY_ZONE (the door area, D-120).
    counting_method: CountingMethod = "LINE_CROSSING"
    #: Normalised polygon around the door opening; used only by DOORWAY_ZONE.
    door_zone: tuple[tuple[float, float], ...] = ()
    faces_visible_when: FacesVisibleWhen = "ENTERING"

    @property
    def doorway(self) -> bool:
        return self.counting_method == "DOORWAY_ZONE"

    @property
    def connection(self) -> tuple[object, ...]:
        """What the stream depends on: a change means reconnecting; anything else applies at the next frame."""
        return (self.host, self.rtsp_port, self.main_path, self.username, self.password_enc)


@dataclass(frozen=True)
class GalleryRow:
    employee_id: str
    template_id: int
    embedding_enc: bytes
    model_name: str
    dim: int


@dataclass(frozen=True)
class ConfigVersion:
    gallery: int
    cameras: str
    recognition: str | None


class Database:
    def __init__(self, settings: Settings) -> None:
        conninfo = make_conninfo(
            host=settings.db_host,
            port=settings.db_port,
            dbname=settings.db_name,
            user=settings.db_user,
            password=settings.db_password.get_secret_value(),
            application_name="krz-worker",
            connect_timeout=5,
            options="-c statement_timeout=5000 -c default_transaction_read_only=on",
        )
        self._pool = ConnectionPool(
            conninfo, min_size=1, max_size=4, open=False, name="krz-worker", kwargs={"row_factory": dict_row}
        )

    def open(self) -> None:
        self._pool.open(wait=False)

    def close(self) -> None:
        self._pool.close()

    def _rows(self, sql: str) -> list[dict[str, Any]]:
        with self._pool.connection(timeout=10) as connection:
            rows: list[dict[str, Any]] = connection.execute(sql).fetchall()  # type: ignore[assignment]
            return rows

    def cameras(self) -> list[CameraConfig]:
        return [
            _camera(row)
            for row in self._rows(
                "SELECT code, name, enabled, host, rtsp_port, onvif_port, main_path, username, password_enc, zone, "
                "wire, confirm_frames, ignore_uturn_sec, liveness_enabled, config_version, counting_method, door_zone, "
                "faces_visible_when "
                "FROM attend.v_worker_camera ORDER BY code"
            )
        ]

    def gallery(self) -> list[GalleryRow]:
        return [
            GalleryRow(
                str(r["employee_id"]), int(r["template_id"]), bytes(r["embedding_enc"]), r["model_name"], r["dim"]
            )
            for r in self._rows(
                "SELECT employee_id, template_id, embedding_enc, model_name, dim FROM attend.v_worker_gallery"
            )
        ]

    def thresholds(self) -> Thresholds:
        rows = self._rows("SELECT * FROM attend.v_worker_recognition")
        if len(rows) != 1:
            raise RuntimeError(f"expected one active threshold profile, found {len(rows)}")
        r = rows[0]
        return Thresholds(
            code=r["code"],
            accept=float(r["accept"]),
            review_band=float(r["review_band"]),
            margin=float(r["margin"]),
            agree_k=int(r["agree_k"]),
            agree_n=int(r["agree_n"]),
            min_eye_px=int(r["min_eye_px"]),
            max_yaw=int(r["max_yaw"]),
            sharp_min=int(r["sharp_min"]),
            quality_min=int(r["quality_min"]),
            recheck_grow=int(r["recheck_grow"]),
            reid_on=bool(r["reid_on"]),
            reid_threshold=float(r["reid_threshold"]),
        )

    def labels(self) -> dict[str, str]:
        """Display names for the overlay (enabled employees with an active face profile)."""
        return {
            str(r["employee_id"]): r["display_name"]
            for r in self._rows("SELECT employee_id, display_name FROM attend.v_worker_employee_label")
        }

    def version(self) -> ConfigVersion:
        rows = self._rows(
            "SELECT gallery_version, camera_config, recognition_profile FROM attend.v_worker_config_version"
        )
        r = rows[0]
        return ConfigVersion(int(r["gallery_version"]), r["camera_config"], r["recognition_profile"])


def _camera(r: dict[str, Any]) -> CameraConfig:
    wire = r["wire"]
    return CameraConfig(
        code=r["code"],
        name=r["name"],
        enabled=bool(r["enabled"]),
        host=r["host"],
        rtsp_port=int(r["rtsp_port"]),
        onvif_port=int(r["onvif_port"]),
        main_path=r["main_path"],
        username=r["username"],
        password_enc=bytes(r["password_enc"]) if r["password_enc"] is not None else None,
        zone=_polygon(r["zone"]),
        wire=(
            Wire(
                (float(wire["a"][0]), float(wire["a"][1])),
                (float(wire["b"][0]), float(wire["b"][1])),
                int(str(wire["inSide"])),
            )
            if wire
            else None
        ),
        confirm_frames=int(r["confirm_frames"]),
        ignore_uturn_sec=int(r["ignore_uturn_sec"]),
        liveness_enabled=bool(r["liveness_enabled"]),
        config_version=int(r["config_version"]),
        counting_method=r["counting_method"],
        door_zone=_polygon(r["door_zone"]),
        faces_visible_when=r["faces_visible_when"],
    )


def _polygon(points: Any) -> tuple[tuple[float, float], ...]:
    return tuple((float(p[0]), float(p[1])) for p in (points or []))

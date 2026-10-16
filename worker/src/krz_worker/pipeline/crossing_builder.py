"""The crossing message, exactly as the Redis contract §1 defines it: every field a string, JSON for the structured
ones, an empty string for "absent". ``docs/test-vectors/crossing-message.json`` checks the shape on both sides."""

from __future__ import annotations

import datetime as dt
import json
import uuid

from uuid6 import uuid7

from krz_worker.pipeline.crops import CropPaths
from krz_worker.recognition.decision import Decision
from krz_worker.recognition.quality import Quality
from krz_worker.timeutil import iso_ms

VERSION = "1"


def new_crossing_uid() -> str:
    """UUIDv7: time-ordered, generated here, the idempotency key in the database."""
    return str(uuid7())


def new_trace_id() -> str:
    return uuid.uuid4().hex[:16]


def build(
    *,
    crossing_uid: str,
    camera: str,
    at: dt.datetime,
    direction: str,
    u_turn: bool,
    decision: Decision,
    quality: Quality | None,
    unknown_cluster: str | None,
    crops: CropPaths,
    track_id: int,
    model_version: str,
    worker_id: str,
    trace_id: str,
) -> dict[str, str]:
    return {
        "v": VERSION,
        "crossing_uid": crossing_uid,
        "camera_code": camera,
        "ts": iso_ms(at),
        "direction": direction,
        "u_turn": "true" if u_turn else "false",
        "decision_hint": decision.hint,
        "method": decision.method,
        "employee_id": decision.employee_id or "",
        "score": _number(decision.score),
        "second_score": _number(decision.second_score),
        "margin": _number(decision.margin),
        "agree_k": "" if decision.agree_k is None else str(decision.agree_k),
        "agree_n": "" if decision.agree_n is None else str(decision.agree_n),
        "reid_score": _number(decision.reid_score),
        "liveness": decision.liveness,
        "liveness_score": _number(decision.liveness_score),
        "quality": _json(quality.as_message()) if quality is not None else "",
        "candidates": _json([{"employee_id": m.employee_id, "score": m.score} for m in decision.candidates])
        if decision.candidates
        else "",
        "unknown_cluster": unknown_cluster or "",
        "face_crop": crops.face,
        "face_crop_blur": crops.face_blur,
        "body_crop": crops.body,
        "body_crop_blur": crops.body_blur,
        "track_id": str(track_id),
        "model_version": model_version,
        "worker_id": worker_id,
        "trace_id": trace_id,
    }


def _number(value: float | None) -> str:
    return "" if value is None else f"{value:.4f}"


def _json(value: object) -> str:
    return json.dumps(value, separators=(",", ":"))

"""Enrolment capture at an entry point (D-118): the backend starts a capture on a running camera with its template bar,
polls the angle groups while the person walks through, and stops it to receive the samples (face crops as JPEG,
embeddings, quality, angle, light, liveness). Samples live only in memory and leave only through the stop answer."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field

from krz_worker.api.auth import require_token
from krz_worker.api.errors import ApiError
from krz_worker.context import Context
from krz_worker.pipeline.enrol_capture import MAX_SECONDS, CaptureRules
from krz_worker.timeutil import iso_ms, now_utc

router = APIRouter(dependencies=[Depends(require_token)])

SESSION_PATTERN = r"^ES-\d{6,}$"


class CaptureStart(BaseModel):
    session: str = Field(pattern=SESSION_PATTERN)
    duration_s: int = Field(ge=10, le=MAX_SECONDS)
    min_eye_px: int = Field(ge=10, le=500)
    min_quality: int = Field(ge=0, le=100)
    min_sharp: int = Field(ge=0, le=100)
    max_yaw: float = Field(ge=5, le=60)


@router.post("/v1/cameras/{code}/captures")
def start_capture(request: Request, code: str, body: CaptureStart) -> dict[str, Any]:
    context: Context = request.app.state.context
    if code not in context.pipelines:
        raise ApiError(404, "BAD_REQUEST", "No running pipeline for this camera")
    rules = CaptureRules(body.min_eye_px, body.min_quality, body.min_sharp, body.max_yaw)
    capture = context.captures.start(code, body.session, body.duration_s, rules)
    return {"session": capture.session, "until": iso_ms(capture.until)}


@router.get("/v1/cameras/{code}/captures/{session}")
def capture_status(request: Request, code: str, session: str) -> dict[str, Any]:
    context: Context = request.app.state.context
    capture = context.captures.get(code, session)
    if capture is None:
        raise ApiError(404, "BAD_REQUEST", "No such capture")
    return capture.status(now_utc())


@router.post("/v1/cameras/{code}/captures/{session}/stop")
def stop_capture(request: Request, code: str, session: str) -> dict[str, Any]:
    """Idempotent: a capture already stopped (or lost with a worker restart) has no samples left to hand over."""
    context: Context = request.app.state.context
    capture = context.captures.stop(code, session)
    return {"samples": capture.result() if capture is not None else []}

"""Camera routes: the connection test (RTSP DESCRIBE + first frame, 10 s cap, URL only in memory), the snapshot of
the latest main-stream frame, and calibration test walks."""

from __future__ import annotations

import time
from typing import Any

import cv2
from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel, Field

from krz_worker.api.auth import require_token
from krz_worker.api.errors import ApiError, within
from krz_worker.context import Context
from krz_worker.stream.frame_source import error_code
from krz_worker.stream.rtsp import build_url, open_stream
from krz_worker.timeutil import now_utc

router = APIRouter(dependencies=[Depends(require_token)])

CODE_PATTERN = r"^CAM-\d{2,}$"
SNAPSHOT_WIDTH = 1280
TEST_SECONDS = 10.0


class CameraTest(BaseModel):
    host: str = Field(min_length=1, max_length=253)
    port: int = Field(ge=1, le=65535)
    path: str = Field(pattern=r"^/\S*$", max_length=300)
    username: str | None = Field(default=None, max_length=120)
    password: str | None = Field(default=None, max_length=200)


@router.post("/v1/cameras/test")
async def test_camera(body: CameraTest) -> dict[str, Any]:
    url = build_url(body.host, body.port, body.path, body.username, body.password)
    try:
        return await within(TEST_SECONDS + 2, probe, url)
    except ApiError as error:
        if error.code != "TIMEOUT":
            raise
        return {"ok": False, "error_code": "TIMEOUT"}


def probe(url: str) -> dict[str, Any]:
    started = time.monotonic()
    try:
        container = open_stream(url, timeout=(5.0, 5.0))
    except Exception as error:  # noqa: BLE001 - every failure becomes a typed error code
        return {"ok": False, "error_code": error_code(error)}
    try:
        video = container.streams.video[0]
        frame = next(iter(container.decode(video)), None)
        if frame is None:
            return {"ok": False, "error_code": "UNSUPPORTED_CODEC"}
        rate = video.average_rate
        return {
            "ok": True,
            "codec": video.codec_context.name,
            "resolution": f"{frame.width}x{frame.height}",
            "fps": round(float(rate), 2) if rate else None,
            "latency_ms": round((time.monotonic() - started) * 1000),
        }
    except Exception as error:  # noqa: BLE001 - every failure becomes a typed error code
        return {"ok": False, "error_code": error_code(error)}
    finally:
        container.close()


def _pipeline(context: Context, code: str) -> Any:
    pipeline = context.pipelines.get(code)
    if pipeline is None:
        raise ApiError(404, "BAD_REQUEST", "No running pipeline for this camera")
    return pipeline


@router.get("/v1/cameras/{code}/snapshot", response_class=Response)
def snapshot(request: Request, code: str, overlays: bool = False) -> Response:
    """The latest main-stream frame, at most 1280 px wide; overlays are drawn by the browser, never here."""
    frame = _pipeline(request.app.state.context, code).latest
    if frame is None:
        raise ApiError(503, "UNREACHABLE", "No frame from this camera yet")
    image = frame.image
    height, width = image.shape[:2]
    if width > SNAPSHOT_WIDTH:
        image = cv2.resize(
            image, (SNAPSHOT_WIDTH, round(height * SNAPSHOT_WIDTH / width)), interpolation=cv2.INTER_AREA
        )
    ok, jpeg = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, 85])
    if not ok:
        raise ApiError(500, "BAD_REQUEST", "Frame could not be encoded")
    return Response(jpeg.tobytes(), media_type="image/jpeg", headers={"Cache-Control": "no-store"})


class CalibrationStart(BaseModel):
    duration_s: int = Field(ge=10, le=600)


@router.post("/v1/cameras/{code}/calibration")
def start_calibration(request: Request, code: str, body: CalibrationStart) -> dict[str, str]:
    context: Context = request.app.state.context
    _pipeline(context, code)
    return {"run_id": context.calibrations.start(code, body.duration_s).run_id}


@router.get("/v1/cameras/{code}/calibration/{run_id}")
def calibration(request: Request, code: str, run_id: str) -> dict[str, Any]:
    run = request.app.state.context.calibrations.get(code, run_id)
    if run is None:
        raise ApiError(404, "BAD_REQUEST", "No such calibration run")
    return {"status": run.status(now_utc()), "crossings": [c.as_dict() for c in run.crossings]}

"""``GET /health``: liveness and readiness without anything sensitive (no token needed)."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Request

from krz_worker import gpu
from krz_worker.context import Context

router = APIRouter()


def status(context: Context) -> dict[str, Any]:
    cameras = [
        {"code": code, "status": pipeline.source.info.status, "fps": pipeline.fps}
        for code, pipeline in sorted(context.pipelines.items())
    ]
    models = context.models
    if not context.ready:
        state = "starting"
    elif any(c["status"] != "live" for c in cameras):
        state = "degraded"
    else:
        state = "ok"
    return {
        "status": state,
        "version": context.settings.app_version,
        "provider": models.provider if models is not None else context.settings.execution_provider,
        "gpu": gpu.info(context.settings.gpu_device),
        "cameras": cameras,
        "models": [
            {"name": m.name, "version": m.version, "sha256": m.sha256, "licence": m.licence}
            for m in (models.files if models is not None else ())
        ],
    }


@router.get("/health")
def health(request: Request) -> dict[str, Any]:
    return status(request.app.state.context)

"""The worker's internal API (FastAPI on ``worker:8090``, internal network only; the dev port is 127.0.0.1 only).

No docs or schema routes are served. Every route except /health needs ``X-Worker-Token``; bodies over 10 MB are
refused; errors are ``{code, message}``; request bodies, images and embeddings are never logged.
"""

from __future__ import annotations

from fastapi import FastAPI

from krz_worker.api import (
    errors,
    routes_cameras,
    routes_capture,
    routes_discovery,
    routes_faces,
    routes_health,
)
from krz_worker.context import Context


def create(context: Context) -> FastAPI:
    app = FastAPI(title="KRZ Attend worker", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.context = context
    errors.install(app)
    for module in (routes_health, routes_faces, routes_cameras, routes_capture, routes_discovery):
        app.include_router(module.router)
    return app

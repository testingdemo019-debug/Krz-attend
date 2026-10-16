"""The error envelope ``{code, message}`` and the request size limit (10 MB) of the internal API."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from typing import Any, TypeVar

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
from starlette.types import ASGIApp, Message, Receive, Scope, Send

MAX_BODY = 10 * 1024 * 1024
T = TypeVar("T")


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def install(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def api_error(_: Request, error: ApiError) -> JSONResponse:
        return JSONResponse({"code": error.code, "message": error.message}, status_code=error.status)

    @app.exception_handler(RequestValidationError)
    async def invalid(_: Request, error: RequestValidationError) -> JSONResponse:
        fields = ", ".join(".".join(str(p) for p in e["loc"][1:]) for e in error.errors())
        return JSONResponse({"code": "BAD_REQUEST", "message": f"Invalid request: {fields}"}, status_code=400)

    app.add_middleware(BodyLimit)


async def within(seconds: float, work: Callable[..., T], *args: Any) -> T:
    """Run blocking work off the event loop with a time limit (the route's timeout)."""
    try:
        return await asyncio.wait_for(run_in_threadpool(work, *args), timeout=seconds)
    except TimeoutError as error:
        raise ApiError(504, "TIMEOUT", "The worker took too long") from error


class BodyLimit:
    """Refuses bodies over 10 MB, declared or streamed, before a route reads them."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        declared = dict(scope.get("headers") or []).get(b"content-length")
        if declared is not None and declared.isdigit() and int(declared) > MAX_BODY:
            await _too_large(send)
            return
        seen = 0

        async def limited() -> Message:
            nonlocal seen
            message = await receive()
            if message["type"] == "http.request":
                seen += len(message.get("body", b""))
                if seen > MAX_BODY:
                    raise ApiError(413, "BAD_REQUEST", "Request body over 10 MB")
            return message

        await self.app(scope, limited, send)


async def _too_large(send: Send) -> None:
    body = b'{"code":"BAD_REQUEST","message":"Request body over 10 MB"}'
    await send(
        {
            "type": "http.response.start",
            "status": 413,
            "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())],
        }
    )
    await send({"type": "http.response.body", "body": body})

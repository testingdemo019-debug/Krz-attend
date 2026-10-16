"""``X-Worker-Token`` on every route except /health, compared in constant time."""

from __future__ import annotations

import hmac

from fastapi import Header, Request

from krz_worker.api.errors import ApiError


def require_token(request: Request, x_worker_token: str | None = Header(default=None)) -> None:
    expected: str = request.app.state.context.settings.api_token.get_secret_value()
    if x_worker_token is None or not hmac.compare_digest(x_worker_token.encode(), expected.encode()):
        raise ApiError(401, "UNAUTHORIZED", "Missing or wrong X-Worker-Token")

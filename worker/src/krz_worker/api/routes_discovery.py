"""``POST /v1/discovery/onvif``: "Find cameras on the network" by a unicast WS-Discovery probe of a /24 at most."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field

from krz_worker.api.auth import require_token
from krz_worker.api.errors import ApiError, within
from krz_worker.stream import onvif

router = APIRouter(dependencies=[Depends(require_token)])


class DiscoveryRequest(BaseModel):
    subnet: str | None = Field(default=None, max_length=18)
    timeout_s: float = Field(default=4, ge=1, le=10)


@router.post("/v1/discovery/onvif")
async def discover(request: Request, body: DiscoveryRequest) -> list[dict[str, Any]]:
    subnet = body.subnet or request.app.state.context.settings.discovery_subnet
    try:
        onvif.hosts(subnet)
    except ValueError as error:
        raise ApiError(400, "BAD_REQUEST", str(error)) from error
    found = await within(body.timeout_s + 3, onvif.discover, subnet, body.timeout_s)
    return [{"ip": d.ip, "manufacturer": d.manufacturer, "model": d.model, "onvif_url": d.onvif_url} for d in found]

"""The two ONVIF calls the worker makes, as minimal SOAP without a client library:

- the camera's clock (``GetSystemDateAndTime``, unauthenticated, 3 s timeout) for the heartbeat's ``clock_offset_s``;
- "Find cameras": a **unicast** WS-Discovery probe to UDP 3702 of every host of a subnet (at most a /24). Multicast does
  not leave a Docker bridge or Docker Desktop, so it is not used (PENDING P-05).

Replies come from the network, so their XML is parsed with defusedxml.
"""

from __future__ import annotations

import datetime as dt
import ipaddress
import socket
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from urllib.parse import unquote

from defusedxml import ElementTree

from krz_worker.timeutil import now_utc

_DATE_TIME_REQUEST = (
    '<?xml version="1.0" encoding="UTF-8"?>'
    '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">'
    '<s:Body><GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/></s:Body></s:Envelope>'
)
_PROBE = (
    '<?xml version="1.0" encoding="UTF-8"?>'
    '<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" '
    'xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" '
    'xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" '
    'xmlns:dn="http://www.onvif.org/ver10/network/wsdl">'
    "<e:Header><w:MessageID>uuid:{id}</w:MessageID>"
    "<w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
    "<w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>"
    "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"
)
DISCOVERY_PORT = 3702
MAX_HOSTS = 256


def clock_offset(host: str, port: int, timeout: float = 3.0) -> float | None:
    """Camera clock minus host clock in seconds, or None when the camera does not answer the call."""
    request = urllib.request.Request(
        f"http://{host}:{port}/onvif/device_service",
        data=_DATE_TIME_REQUEST.encode(),
        headers={"Content-Type": "application/soap+xml; charset=utf-8"},
    )
    asked = now_utc()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 - http to the camera
            body = response.read(65536)
    except (urllib.error.URLError, OSError, TimeoutError):
        return None
    camera = _utc_date_time(body)
    if camera is None:
        return None
    halfway = asked + (now_utc() - asked) / 2
    return round((camera - halfway).total_seconds(), 2)


def _utc_date_time(body: bytes) -> dt.datetime | None:
    try:
        root = ElementTree.fromstring(body)
    except ElementTree.ParseError:
        return None
    values: dict[str, int] = {}
    utc = next((e for e in root.iter() if e.tag.endswith("UTCDateTime")), None)
    if utc is None:
        return None
    for element in utc.iter():
        name = element.tag.rsplit("}", 1)[-1]
        if name in {"Year", "Month", "Day", "Hour", "Minute", "Second"} and element.text:
            values[name] = int(element.text)
    try:
        return dt.datetime(
            values["Year"],
            values["Month"],
            values["Day"],
            values["Hour"],
            values["Minute"],
            values["Second"],
            tzinfo=dt.UTC,
        )
    except (KeyError, ValueError):
        return None


@dataclass(frozen=True)
class Discovered:
    ip: str
    manufacturer: str | None
    model: str | None
    onvif_url: str | None


def hosts(subnet: str) -> list[str]:
    """The host addresses of an IPv4 subnet of at most 256 addresses (a /24)."""
    network = ipaddress.ip_network(subnet, strict=False)
    if network.version != 4 or network.num_addresses > MAX_HOSTS:
        raise ValueError("Use an IPv4 subnet of /24 or smaller")
    return [str(h) for h in network.hosts()] or [str(network.network_address)]


def discover(subnet: str, timeout: float) -> list[Discovered]:
    targets = hosts(subnet)
    found: dict[str, Discovered] = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(0.2)
        message = _PROBE.format(id=uuid.uuid4()).encode()
        for target in targets:
            try:
                sock.sendto(message, (target, DISCOVERY_PORT))
            except OSError:
                continue
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                data, (address, _port) = sock.recvfrom(65536)
            except TimeoutError:
                continue
            except OSError:
                break
            if address in targets and address not in found:
                found[address] = _probe_match(address, data)
    return sorted(found.values(), key=lambda d: ipaddress.ip_address(d.ip))


def _probe_match(address: str, data: bytes) -> Discovered:
    try:
        root = ElementTree.fromstring(data)
    except ElementTree.ParseError:
        return Discovered(address, None, None, None)
    scopes = " ".join(e.text or "" for e in root.iter() if e.tag.endswith("Scopes")).split()
    addresses = " ".join(e.text or "" for e in root.iter() if e.tag.endswith("XAddrs")).split()

    def scope(kind: str) -> str | None:
        prefix = f"onvif://www.onvif.org/{kind}/"
        return next((unquote(s[len(prefix) :]) for s in scopes if s.startswith(prefix)), None)

    return Discovered(
        address,
        scope("name"),
        scope("hardware"),
        next((a for a in addresses if address in a), addresses[0] if addresses else None),
    )

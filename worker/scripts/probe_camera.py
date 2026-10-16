"""Check that the development camera is reachable: codec, resolution, fps and time to the first frame of both streams.

Reads CAMERA_01_* from deploy/.env inside this process and builds the RTSP URLs in memory; the credentials never
appear on a command line, on screen or in a log (only the masked URL is printed).

Usage (from worker/, with the worker's virtual environment):
    python scripts/probe_camera.py [--seconds 5]
"""

from __future__ import annotations

import argparse
import time
from fractions import Fraction

from dotenv import dotenv_values

from krz_worker.config import DEPLOY_DIR
from krz_worker.stream.rtsp import build_url, mask, open_stream


def probe(url: str, seconds: float) -> str:
    started = time.monotonic()
    try:
        container = open_stream(url)
    except Exception as error:  # noqa: BLE001 - report any failure as one line
        return f"FAILED to open: {mask(type(error).__name__ + ': ' + str(error))}"
    try:
        video = container.streams.video[0]
        first_frame: float | None = None
        frames = 0
        last = None
        for decoded in container.decode(video):
            last = decoded
            if first_frame is None:
                first_frame = time.monotonic()
            frames += 1
            if time.monotonic() - first_frame >= seconds:
                break
        if first_frame is None or last is None:
            return "FAILED: no frame"
        elapsed = max(time.monotonic() - first_frame, 1e-3)
        rate: Fraction | None = video.average_rate
        return (
            f"codec {video.codec_context.name}, {last.width}x{last.height}, "
            f"{frames / elapsed:.1f} fps decoded (stream says {float(rate) if rate else '?'}), "
            f"first frame after {(first_frame - started) * 1000:.0f} ms"
        )
    finally:
        container.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--seconds", type=float, default=5.0, help="how long to decode each stream")
    args = parser.parse_args()
    env = dotenv_values(DEPLOY_DIR / ".env")
    host = env.get("CAMERA_01_HOST")
    if not host:
        raise SystemExit("CAMERA_01_HOST is not set in deploy/.env")
    port = int(env.get("CAMERA_01_RTSP_PORT") or 554)
    for label, key in (
        ("main stream (worker)", "CAMERA_01_MAIN_PATH"),
        ("sub stream (Live View)", "CAMERA_01_SUB_PATH"),
    ):
        path = env.get(key)
        if not path:
            print(f"{label}: {key} is not set")
            continue
        url = build_url(host, port, path, env.get("CAMERA_01_USERNAME"), env.get("CAMERA_01_PASSWORD"))
        print(f"{label} {mask(url)}: {probe(url, args.seconds)}")


if __name__ == "__main__":
    main()

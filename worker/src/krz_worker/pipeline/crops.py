"""The crossing's crops: the best face (25 % margin) and the best body, JPEG quality 85, each with a blurred variant
(strong Gaussian blur plus pixelation of the face area). All four are encrypted and written atomically, mode 0640, to
``crops/<yyyy>/<mm>/<dd>/<crossing_uid>_<face|face_blur|body|body_blur>.jpg.enc`` (IST day). The decrypted images never
touch the disk.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

from krz_worker.crypto import KeyRing
from krz_worker.models.base import Image
from krz_worker.timeutil import ist_day

JPEG_QUALITY = 85
FACE_MARGIN = 0.25
PIXEL_BLOCKS = 12


@dataclass(frozen=True)
class CropPaths:
    """Paths relative to ``crops/`` as the crossing message carries them; empty when not captured."""

    face: str = ""
    face_blur: str = ""
    body: str = ""
    body_blur: str = ""


def face_crop(frame: Image, box: tuple[float, float, float, float]) -> Image:
    x, y, w, h = box
    mx, my = w * FACE_MARGIN, h * FACE_MARGIN
    return _cut(frame, (x - mx, y - my, x + w + mx, y + h + my))


def body_crop(frame: Image, box: tuple[float, float, float, float]) -> Image:
    return _cut(frame, box)


def _cut(frame: Image, xyxy: tuple[float, float, float, float]) -> Image:
    height, width = frame.shape[:2]
    x1, y1 = max(0, int(xyxy[0])), max(0, int(xyxy[1]))
    x2, y2 = min(width, int(np.ceil(xyxy[2]))), min(height, int(np.ceil(xyxy[3])))
    return frame[y1:y2, x1:x2].copy()


def blurred(image: Image, area: tuple[float, float, float, float] | None) -> Image:
    """A strong blur of the whole crop plus pixelation of ``area`` (x, y, w, h; the whole crop when None)."""
    height, width = image.shape[:2]
    if height == 0 or width == 0:
        return image
    kernel = max(3, (min(height, width) // 6) | 1)
    result: Image = cv2.GaussianBlur(image, (kernel, kernel), 0)
    x, y, w, h = area if area is not None else (0.0, 0.0, float(width), float(height))
    x1, y1 = max(0, int(x)), max(0, int(y))
    x2, y2 = min(width, int(x + w)), min(height, int(y + h))
    if x2 - x1 >= 2 and y2 - y1 >= 2:
        region = result[y1:y2, x1:x2]
        small = cv2.resize(region, (PIXEL_BLOCKS, PIXEL_BLOCKS), interpolation=cv2.INTER_LINEAR)
        result[y1:y2, x1:x2] = cv2.resize(small, (x2 - x1, y2 - y1), interpolation=cv2.INTER_NEAREST)
    return result


def write(
    ring: KeyRing,
    crops_dir: Path,
    crossing_uid: str,
    at: dt.datetime,
    face: Image | None,
    body: Image | None,
    face_in_body: tuple[float, float, float, float] | None,
) -> CropPaths:
    day = ist_day(at)
    folder = f"{day:%Y}/{day:%m}/{day:%d}"

    def store(kind: str, image: Image) -> str:
        ok, jpeg = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
        if not ok:
            return ""
        relative = f"{folder}/{crossing_uid}_{kind}.jpg.enc"
        ring.write_file(crops_dir / relative, jpeg.tobytes())
        return relative

    paths: dict[str, str] = {}
    if face is not None and face.size:
        paths["face"] = store("face", face)
        paths["face_blur"] = store("face_blur", blurred(face, None))
    if body is not None and body.size:
        head = face_in_body or (0.0, 0.0, float(body.shape[1]), body.shape[0] * 0.25)
        paths["body"] = store("body", body)
        paths["body_blur"] = store("body_blur", blurred(body, head))
    return CropPaths(**paths)

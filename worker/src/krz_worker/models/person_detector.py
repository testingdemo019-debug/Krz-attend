"""YOLOX-s (Apache-2.0, Megvii) on ONNX Runtime: people in the frame.

The release ONNX takes a 640x640 BGR letterbox (top-left aligned, padding 114, no normalisation) and returns raw
per-anchor outputs that are decoded here with the stride grids, as the YOLOX ONNX Runtime demo does.
"""

from __future__ import annotations

from typing import Any

import cv2
import numpy as np
from numpy.typing import NDArray

from krz_worker.models.base import Image, ModelInfo, Person, PersonDetector

INPUT = 640
STRIDES = (8, 16, 32)
PERSON_CLASS = 0
MIN_SCORE = 0.5
NMS_IOU = 0.45


class YoloxPersonDetector(PersonDetector):
    def __init__(self, info: ModelInfo, session: Any) -> None:
        super().__init__(info)
        self._session = session
        self._input = session.get_inputs()[0].name
        self._grids, self._strides = _grids()

    def detect(self, frame: Image) -> list[Person]:
        blob, ratio = letterbox(frame)
        raw = self._session.run(None, {self._input: blob})[0][0]
        return decode(raw, self._grids, self._strides, ratio)

    def warm_up(self) -> None:
        self.detect(np.zeros((INPUT, INPUT, 3), dtype=np.uint8))


def letterbox(frame: Image) -> tuple[NDArray[np.float32], float]:
    height, width = frame.shape[:2]
    ratio = min(INPUT / height, INPUT / width)
    resized = cv2.resize(frame, (int(width * ratio), int(height * ratio)), interpolation=cv2.INTER_LINEAR)
    padded = np.full((INPUT, INPUT, 3), 114, dtype=np.uint8)
    padded[: resized.shape[0], : resized.shape[1]] = resized
    blob = np.ascontiguousarray(padded.transpose(2, 0, 1)[None].astype(np.float32))
    return blob, ratio


def _grids() -> tuple[NDArray[np.float32], NDArray[np.float32]]:
    grids, strides = [], []
    for stride in STRIDES:
        size = INPUT // stride
        xs, ys = np.meshgrid(np.arange(size), np.arange(size))
        grid = np.stack((xs, ys), axis=2).reshape(-1, 2)
        grids.append(grid)
        strides.append(np.full((grid.shape[0], 1), stride))
    return np.concatenate(grids).astype(np.float32), np.concatenate(strides).astype(np.float32)


def decode(
    raw: NDArray[np.float32], grids: NDArray[np.float32], strides: NDArray[np.float32], ratio: float
) -> list[Person]:
    """Person boxes in frame pixels: decode, score = objectness x person class, NMS."""
    scores = raw[:, 4] * raw[:, 5 + PERSON_CLASS]
    keep = scores >= MIN_SCORE
    if not np.any(keep):
        return []
    centres = (raw[keep, :2] + grids[keep]) * strides[keep]
    sizes = np.exp(raw[keep, 2:4]) * strides[keep]
    boxes = np.asarray(np.concatenate((centres - sizes / 2, sizes), axis=1), dtype=np.float32) / ratio  # x, y, w, h
    chosen = cv2.dnn.NMSBoxes(boxes.tolist(), scores[keep].tolist(), MIN_SCORE, NMS_IOU)
    people = []
    for index in np.array(chosen).reshape(-1):
        x, y, w, h = (float(v) for v in boxes[index])
        people.append(Person((x, y, x + w, y + h), float(scores[keep][index])))
    return people

"""Passive liveness: Silent-Face MiniFASNetV2 (scale 2.7) and MiniFASNetV1SE (scale 4.0), Apache-2.0, fused.

As the reference implementation: each model sees an 80x80 crop around the face box enlarged by its scale (shifted to
stay inside the frame), BGR values 0-255 without normalisation; the two softmax outputs are summed and class 1 means a
real face. The score is the averaged probability of "real".
"""

from __future__ import annotations

from typing import Any

import cv2
import numpy as np
from numpy.typing import NDArray

from krz_worker.models.base import Face, Image, Liveness, LivenessChecker, ModelInfo

SIZE = 80
REAL = 1


class MiniFasNetLiveness(LivenessChecker):
    """``sessions`` pairs each scale with its ONNX Runtime session."""

    def __init__(self, info: ModelInfo, sessions: list[tuple[float, Any]]) -> None:
        super().__init__(info)
        self._sessions = [(scale, session, session.get_inputs()[0].name) for scale, session in sessions]

    def check(self, frame: Image, face: Face) -> Liveness:
        fused = np.zeros(3, dtype=np.float32)
        for scale, session, input_name in self._sessions:
            patch = crop(frame, face.box, scale)
            blob = np.ascontiguousarray(patch.transpose(2, 0, 1)[None].astype(np.float32))
            logits = np.asarray(session.run(None, {input_name: blob})[0][0], dtype=np.float32)
            fused += softmax(logits)
        return Liveness(passed=int(np.argmax(fused)) == REAL, score=float(fused[REAL] / len(self._sessions)))

    def warm_up(self) -> None:
        self.check(np.zeros((240, 240, 3), dtype=np.uint8), Face((80.0, 80.0, 80.0, 80.0), ((0.0, 0.0),) * 5, 1.0))


def crop(frame: Image, box: tuple[float, float, float, float], scale: float) -> Image:
    """The reference CropImage: the box scaled about its centre (no further than the frame allows), moved inside."""
    height, width = frame.shape[:2]
    x, y, w, h = box
    w, h = max(w, 1.0), max(h, 1.0)
    scale = min((height - 1) / h, (width - 1) / w, scale)
    new_w, new_h = w * scale, h * scale
    cx, cy = x + w / 2, y + h / 2
    left, top = cx - new_w / 2, cy - new_h / 2
    right, bottom = cx + new_w / 2, cy + new_h / 2
    if left < 0:
        right -= left
        left = 0
    if top < 0:
        bottom -= top
        top = 0
    if right > width - 1:
        left -= right - width + 1
        right = width - 1
    if bottom > height - 1:
        top -= bottom - height + 1
        bottom = height - 1
    patch = frame[int(top) : int(bottom) + 1, int(left) : int(right) + 1]
    resized: Image = cv2.resize(patch, (SIZE, SIZE))
    return resized


def softmax(logits: NDArray[np.float32]) -> NDArray[np.float32]:
    shifted = np.exp(logits - np.max(logits))
    result: NDArray[np.float32] = (shifted / shifted.sum()).astype(np.float32)
    return result

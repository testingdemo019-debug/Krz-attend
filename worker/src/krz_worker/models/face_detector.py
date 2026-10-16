"""YuNet (MIT, OpenCV Zoo) through ``cv2.FaceDetectorYN``: fast on the CPU, run on head regions at native resolution.

One detector serves every camera thread and the API. Its input size is state set before each call, so a call from
another thread in between made OpenCV fail (``buf.shape() == m.shape()``); a lock keeps size and detection together.
"""

from __future__ import annotations

import threading
from pathlib import Path

import cv2
import numpy as np

from krz_worker.models.base import Face, FaceDetector, Image, ModelInfo

SCORE_THRESHOLD = 0.7
NMS_THRESHOLD = 0.3
TOP_K = 50


class YuNetFaceDetector(FaceDetector):
    def __init__(self, info: ModelInfo, path: Path) -> None:
        super().__init__(info)
        self._detector = cv2.FaceDetectorYN.create(str(path), "", (320, 320), SCORE_THRESHOLD, NMS_THRESHOLD, TOP_K)
        self._lock = threading.Lock()

    def detect(self, image: Image) -> list[Face]:
        height, width = image.shape[:2]
        if height < 10 or width < 10:
            return []
        with self._lock:
            self._detector.setInputSize((width, height))
            _, rows = self._detector.detect(image)
        if rows is None:
            return []
        return [
            Face(
                (float(r[0]), float(r[1]), float(r[2]), float(r[3])),
                tuple((float(r[4 + 2 * i]), float(r[5 + 2 * i])) for i in range(5)),
                float(r[14]),
            )
            for r in rows
        ]

    def warm_up(self) -> None:
        self.detect(np.zeros((160, 160, 3), dtype=np.uint8))

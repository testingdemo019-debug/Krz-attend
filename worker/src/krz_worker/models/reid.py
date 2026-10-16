"""OSNet x1.0 (torchreid, MSMT17) on ONNX Runtime: 512-d back-view body embeddings for same-day Re-ID.

Input: person crops resized to 256x128, RGB, ImageNet mean and standard deviation (torchreid's test transform).
"""

from __future__ import annotations

from typing import Any

import cv2
import numpy as np

from krz_worker.models.base import Image, ModelInfo, ReIdEmbedder, Vectors
from krz_worker.models.face_embedder import normalise

HEIGHT, WIDTH = 256, 128
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


class OsnetReIdEmbedder(ReIdEmbedder):
    dim = 512

    def __init__(self, info: ModelInfo, session: Any) -> None:
        super().__init__(info)
        self._session = session
        self._input = session.get_inputs()[0].name

    def embed(self, bodies: list[Image]) -> Vectors:
        if not bodies:
            return np.zeros((0, self.dim), dtype=np.float32)
        batch = np.stack(
            [
                (cv2.cvtColor(cv2.resize(b, (WIDTH, HEIGHT)), cv2.COLOR_BGR2RGB).astype(np.float32) / 255 - MEAN) / STD
                for b in bodies
            ]
        )
        features = self._session.run(None, {self._input: np.ascontiguousarray(batch.transpose(0, 3, 1, 2))})[0]
        return normalise(np.asarray(features, dtype=np.float32))

    def warm_up(self) -> None:
        self.embed([np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)])

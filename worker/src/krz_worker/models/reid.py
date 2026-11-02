"""NVIDIA ReIdentificationNet (TAO, ResNet50) on TensorRT: 256-d body embeddings for same-day Re-ID.

Input: person crops resized to 256x128, RGB, with TAO's pixel mean and standard deviation. NVIDIA's licence covers
systems with NVIDIA GPUs and its model card names TensorRT as the way to run it, so the session is TensorRT only
(``registry``); where TensorRT is missing there is no embedder and Re-ID is off.
"""

from __future__ import annotations

from typing import Any

import cv2
import numpy as np

from krz_worker.models.base import Image, ModelInfo, ReIdEmbedder, Vectors
from krz_worker.models.face_embedder import normalise

HEIGHT, WIDTH = 256, 128
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.float32(0.226)
#: The batch sizes the TensorRT engine is built for; a size outside them would stop the camera to rebuild it.
MAX_BATCH = 8


class NvidiaReIdEmbedder(ReIdEmbedder):
    dim = 256

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

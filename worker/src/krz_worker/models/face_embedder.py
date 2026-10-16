"""SFace (Apache-2.0, OpenCV Zoo): 128-d face embeddings on ONNX Runtime (GPU), aligned with OpenCV's
``FaceRecognizerSF.alignCrop`` (the alignment the model was trained with)."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import cv2
import numpy as np

from krz_worker.models.base import Face, FaceEmbedder, Image, ModelInfo, Vectors

INPUT_SIZE = 112


class SFaceEmbedder(FaceEmbedder):
    dim = 128

    def __init__(self, info: ModelInfo, path: Path, session: Any) -> None:
        super().__init__(info)
        # OpenCV's recogniser is used for alignCrop only; the embedding runs on ONNX Runtime.
        self._aligner = cv2.FaceRecognizerSF.create(str(path), "")
        self._session = session
        self._input = session.get_inputs()[0].name

    def align(self, image: Image, face: Face) -> Image:
        aligned: Image = self._aligner.alignCrop(image, face.as_row())
        return aligned

    def embed(self, aligned: list[Image]) -> Vectors:
        if not aligned:
            return np.zeros((0, self.dim), dtype=np.float32)
        # As FaceRecognizerSF.feature: RGB, 0-255, no mean or scale.
        batch = np.stack([cv2.cvtColor(a, cv2.COLOR_BGR2RGB) for a in aligned]).astype(np.float32)
        batch = np.ascontiguousarray(batch.transpose(0, 3, 1, 2))
        # The model's batch dimension is fixed at 1.
        rows = [self._session.run(None, {self._input: batch[i : i + 1]})[0][0] for i in range(len(aligned))]
        return normalise(np.asarray(rows, dtype=np.float32))

    def warm_up(self) -> None:
        self.embed([np.zeros((INPUT_SIZE, INPUT_SIZE, 3), dtype=np.uint8)])


def normalise(vectors: Vectors) -> Vectors:
    norms = np.linalg.norm(vectors, axis=1, keepdims=True)
    result: Vectors = (vectors / np.maximum(norms, 1e-12)).astype(np.float32)
    return result

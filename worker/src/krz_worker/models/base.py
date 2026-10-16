"""The model interfaces. Every model sits behind one of these, so swapping a model (for example a commercially licensed
face embedder) means one new class and one change in models.json.

Images are OpenCV BGR ``uint8`` arrays (H x W x 3). Boxes are in the pixels of the image they were found in.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any

import numpy as np
from numpy.typing import NDArray

#: An OpenCV image (BGR uint8, H x W x 3); typed loosely because OpenCV's own stubs return untyped arrays.
Image = NDArray[Any]
Vectors = NDArray[np.float32]


@dataclass(frozen=True)
class ModelInfo:
    """One entry of models.json, verified against the file at start-up."""

    role: str
    name: str
    version: str
    licence: str
    sha256: str
    file: str

    @property
    def tag(self) -> str:
        """``sface-2021dec``: what templates record as their model (the backend splits it at the last dash)."""
        return f"{self.name}-{self.version}"


class Model(ABC):
    def __init__(self, info: ModelInfo) -> None:
        self.info = info

    @property
    def name(self) -> str:
        return self.info.name

    @property
    def version(self) -> str:
        return self.info.version

    @property
    def licence(self) -> str:
        return self.info.licence

    @property
    def sha256(self) -> str:
        return self.info.sha256

    @abstractmethod
    def warm_up(self) -> None:
        """Run once on a dummy input, so the first real frame does not pay for initialisation."""


@dataclass(frozen=True)
class Face:
    """A detected face: box (x, y, w, h), five landmarks (right eye, left eye, nose, right and left mouth corner, as
    seen in the image) and the detector's confidence."""

    box: tuple[float, float, float, float]
    landmarks: tuple[tuple[float, float], ...]
    score: float

    def shifted(self, dx: float, dy: float) -> Face:
        x, y, w, h = self.box
        return Face((x + dx, y + dy, w, h), tuple((px + dx, py + dy) for px, py in self.landmarks), self.score)

    def as_row(self) -> NDArray[np.float32]:
        """OpenCV's 15-value face row (box, landmarks, score), as FaceDetectorYN returns and alignCrop expects."""
        return np.array([*self.box, *(v for point in self.landmarks for v in point), self.score], dtype=np.float32)


@dataclass(frozen=True)
class Person:
    """A detected person: box (x1, y1, x2, y2) and confidence."""

    box: tuple[float, float, float, float]
    score: float


@dataclass(frozen=True)
class Liveness:
    passed: bool
    #: The fused probability that the face is real, 0..1.
    score: float


class FaceDetector(Model):
    @abstractmethod
    def detect(self, image: Image) -> list[Face]: ...


class FaceEmbedder(Model):
    dim: int

    @abstractmethod
    def align(self, image: Image, face: Face) -> Image:
        """The face cropped and aligned to the embedder's input."""

    @abstractmethod
    def embed(self, aligned: list[Image]) -> Vectors:
        """L2-normalised embeddings, one row per aligned face."""


class PersonDetector(Model):
    @abstractmethod
    def detect(self, frame: Image) -> list[Person]: ...


class ReIdEmbedder(Model):
    dim: int

    @abstractmethod
    def embed(self, bodies: list[Image]) -> Vectors:
        """L2-normalised body embeddings, one row per person crop."""


class LivenessChecker(Model):
    @abstractmethod
    def check(self, frame: Image, face: Face) -> Liveness: ...

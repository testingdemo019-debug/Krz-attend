"""Builds the models from models.json and MODELS_DIR: verifies every file's SHA-256 (a mismatch is fatal), opens the
ONNX Runtime sessions on the configured execution provider (TensorRT FP16 with an engine cache, else CUDA, else CPU;
the fallback is logged once) and warms each model up. The Re-ID model runs on TensorRT whatever the provider is
(``reid``); where TensorRT is missing the worker runs without it and Re-ID is off."""

from __future__ import annotations

import hashlib
import importlib
import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import onnxruntime as ort

from krz_worker.config import Settings
from krz_worker.models.base import (
    FaceDetector,
    FaceEmbedder,
    LivenessChecker,
    ModelInfo,
    PersonDetector,
    ReIdEmbedder,
)
from krz_worker.models.face_detector import YuNetFaceDetector
from krz_worker.models.face_embedder import SFaceEmbedder
from krz_worker.models.liveness import MiniFasNetLiveness
from krz_worker.models.person_detector import YoloxPersonDetector
from krz_worker.models.reid import HEIGHT, MAX_BATCH, WIDTH, NvidiaReIdEmbedder

LOG = logging.getLogger(__name__)

_PROVIDERS = {
    "tensorrt": ["TensorrtExecutionProvider", "CUDAExecutionProvider", "CPUExecutionProvider"],
    "cuda": ["CUDAExecutionProvider", "CPUExecutionProvider"],
    "cpu": ["CPUExecutionProvider"],
}


class ModelIntegrityError(RuntimeError):
    """A model file is missing or differs from models.json: the worker must not run on it."""


@dataclass(frozen=True)
class Models:
    face_detector: FaceDetector
    face_embedder: FaceEmbedder
    person_detector: PersonDetector
    #: None where TensorRT is not available: no body embeddings, Re-ID is off.
    reid: ReIdEmbedder | None
    liveness: LivenessChecker
    #: Every catalogue entry, as /health and the worker heartbeat report them.
    files: tuple[ModelInfo, ...]
    #: The execution provider ONNX Runtime actually uses: tensorrt, cuda or cpu.
    provider: str

    @property
    def model_version(self) -> str:
        """The crossing message's ``model_version``: yunet-2023mar+sface-2021dec+reidentificationnet-deployable_v1.2."""
        return "+".join(m.info.tag for m in (self.face_detector, self.face_embedder, self.reid) if m is not None)


def load(settings: Settings) -> Models:
    entries = json.loads(settings.models_catalog.read_text(encoding="utf-8"))["models"]
    infos = {entry["file"]: _verified(entry, settings.models_dir) for entry in entries}
    by_role: dict[str, list[ModelInfo]] = {}
    for info in infos.values():
        by_role.setdefault(info.role, []).append(info)
    sessions = _SessionFactory(settings)

    def path(info: ModelInfo) -> Path:
        return settings.models_dir / info.file

    face_detector = by_role["face_detector"][0]
    face_embedder = by_role["face_embedder"][0]
    person_detector = by_role["person_detector"][0]
    reid = by_role["reid"][0]
    liveness = sorted(by_role["liveness"], key=_scale)
    reid_session = sessions.open_on_tensorrt(path(reid))
    if reid_session is None:
        LOG.error(
            "Back-view Re-ID is off: %s runs only on TensorRT with an NVIDIA GPU, which is not available here "
            "(see the worker's gpu extra). An OUT without a face goes to review.",
            reid.file,
        )
    models = Models(
        face_detector=YuNetFaceDetector(face_detector, path(face_detector)),
        face_embedder=SFaceEmbedder(face_embedder, path(face_embedder), sessions.open(path(face_embedder))),
        person_detector=YoloxPersonDetector(person_detector, sessions.open(path(person_detector))),
        reid=NvidiaReIdEmbedder(reid, reid_session) if reid_session is not None else None,
        liveness=MiniFasNetLiveness(
            ModelInfo(
                role="liveness",
                name="minifasnet",
                version="+".join(f"{info.name.removeprefix('minifasnet-')}-{_scale(info)}" for info in liveness),
                licence=liveness[0].licence,
                sha256=hashlib.sha256("".join(info.sha256 for info in liveness).encode()).hexdigest(),
                file=",".join(info.file for info in liveness),
            ),
            [(_scale(info), sessions.open(path(info))) for info in liveness],
        ),
        files=tuple(infos.values()),
        provider=sessions.provider,
    )
    for model in (models.face_detector, models.face_embedder, models.person_detector, models.reid, models.liveness):
        if model is not None:
            model.warm_up()
    LOG.info("Models ready on %s: %s", models.provider, models.model_version)
    return models


def _verified(entry: dict[str, Any], models_dir: Path) -> ModelInfo:
    file = models_dir / entry["file"]
    expected = entry.get("sha256")
    if expected is None:
        raise ModelIntegrityError(f"{entry['file']} has no SHA-256 in models.json (run scripts/download_models.py)")
    if not file.is_file():
        raise ModelIntegrityError(f"{file} is missing (run scripts/download_models.py)")
    digest = hashlib.sha256()
    with file.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise ModelIntegrityError(f"{entry['file']}: SHA-256 {digest.hexdigest()} differs from models.json")
    return ModelInfo(entry["role"], entry["name"], entry["version"], entry["licence"], expected, entry["file"])


def _scale(info: ModelInfo) -> float:
    """MiniFASNet's crop scale from its version, ``2.7_80x80`` -> 2.7."""
    return float(info.version.split("_")[0])


class _SessionFactory:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        wanted = _PROVIDERS[settings.execution_provider]
        available = set(ort.get_available_providers())
        if wanted[0] != "CPUExecutionProvider" and "CUDAExecutionProvider" in available:
            # The GPU image has CUDA and cuDNN as NVIDIA pip wheels, which ONNX Runtime does not find by itself.
            ort.preload_dlls()
        options = {
            "TensorrtExecutionProvider": {
                "device_id": settings.gpu_device,
                "trt_fp16_enable": True,
                "trt_engine_cache_enable": True,
                "trt_engine_cache_path": str(settings.trt_cache_dir),
            },
            "CUDAExecutionProvider": {"device_id": settings.gpu_device},
            "CPUExecutionProvider": {},
        }
        self._providers = [(name, options[name]) for name in wanted if name in available]
        if self._providers[0][0] != wanted[0]:
            LOG.warning("%s is not available here; using %s", wanted[0], self._providers[0][0])
        self.provider = {
            "TensorrtExecutionProvider": "tensorrt",
            "CUDAExecutionProvider": "cuda",
            "CPUExecutionProvider": "cpu",
        }[self._providers[0][0]]
        self._options = ort.SessionOptions()
        self._options.log_severity_level = 3  # errors only (SFace lists its weights as inputs, a harmless warning)

    def open(self, path: Path) -> Any:
        session = ort.InferenceSession(str(path), self._options, providers=self._providers)
        used = session.get_providers()[0]
        if used != self._providers[0][0]:
            LOG.warning("%s runs on %s instead of %s", path.name, used, self._providers[0][0])
        return session

    def open_on_tensorrt(self, path: Path) -> Any | None:
        """A session on TensorRT and nothing else, or None where TensorRT is missing. Its engine is built once for
        the Re-ID model's batch sizes (about a minute) and cached, so a later start takes seconds."""
        if "TensorrtExecutionProvider" not in ort.get_available_providers():
            return None
        ort.preload_dlls()
        try:
            # NVIDIA's pip wheel: importing it loads the libraries, which ONNX Runtime does not find by itself.
            importlib.import_module("tensorrt_libs")
        except ImportError:
            return None
        self._settings.trt_cache_dir.mkdir(parents=True, exist_ok=True)
        shape = f"3x{HEIGHT}x{WIDTH}"
        options = {
            "device_id": self._settings.gpu_device,
            "trt_fp16_enable": True,
            "trt_engine_cache_enable": True,
            "trt_engine_cache_path": str(self._settings.trt_cache_dir),
            # "input" is the model's input name (models.json).
            "trt_profile_min_shapes": f"input:1x{shape}",
            "trt_profile_opt_shapes": f"input:1x{shape}",
            "trt_profile_max_shapes": f"input:{MAX_BATCH}x{shape}",
        }
        session = ort.InferenceSession(str(path), self._options, providers=[("TensorrtExecutionProvider", options)])
        # ONNX Runtime falls back to the CPU by itself when TensorRT cannot start, for example without a GPU.
        return session if session.get_providers()[0] == "TensorrtExecutionProvider" else None

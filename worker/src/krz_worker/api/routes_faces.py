"""Enrolment support: ``POST /v1/embeddings`` (detection, quality, liveness and the embedding of each capture) and
``POST /v1/duplicates`` (lookalikes in the in-memory gallery). Images are decoded from memory and never written; the
embedding goes only to the backend, which encrypts it; nothing here is logged."""

from __future__ import annotations

import base64
import binascii
from typing import Annotated, Any, Literal

import cv2
import numpy as np
from fastapi import APIRouter, Depends, File, Form, Request, UploadFile
from pydantic import BaseModel, Field

from krz_worker.api.auth import require_token
from krz_worker.api.errors import ApiError, within
from krz_worker.context import Context
from krz_worker.models.base import Face, Image
from krz_worker.models.face_embedder import normalise
from krz_worker.models.registry import Models
from krz_worker.recognition import quality as face_quality

router = APIRouter(dependencies=[Depends(require_token)])

MAX_IMAGES = 10
#: Detection runs on a copy at most this wide/high; landmarks and quality are measured at full resolution.
DETECT_SIDE = 1280


def models_of(context: Context) -> Models:
    if context.models is None:
        raise ApiError(503, "MODEL_NOT_READY", "Models are still loading")
    return context.models


@router.post("/v1/embeddings")
async def embeddings(
    request: Request,
    images: Annotated[list[UploadFile], File()],
    mode: Annotated[Literal["live", "upload"], Form()],
) -> list[dict[str, Any]]:
    if not 1 <= len(images) <= MAX_IMAGES:
        raise ApiError(400, "BAD_REQUEST", f"Send 1 to {MAX_IMAGES} images")
    models = models_of(request.app.state.context)
    data = [await image.read() for image in images]
    return await within(18, lambda: [analyse(models, raw, mode) for raw in data])


def analyse(models: Models, raw: bytes, mode: str) -> dict[str, Any]:
    image = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return {"faces": 0}
    faces = detect(models, image)
    result: dict[str, Any] = {"faces": len(faces)}
    if len(faces) != 1:
        return result
    face = faces[0]
    aligned = models.face_embedder.align(image, face)
    measured = face_quality.measure(face, aligned)
    embedding = models.face_embedder.embed([aligned])[0].astype("<f4")
    result.update(
        {
            "bbox": [round(v, 1) for v in face.box],
            "landmarks": [[round(x, 1), round(y, 1)] for x, y in face.landmarks],
            "quality": {
                "eye_px": measured.eye_px,
                "yaw": measured.yaw,
                "pitch": measured.pitch,
                "sharp": measured.sharp,
                "brightness": measured.brightness,
                "score": measured.score,
            },
            "embedding": base64.b64encode(embedding.tobytes()).decode(),
            "dim": int(embedding.shape[0]),
            "model": models.face_embedder.info.tag,
        }
    )
    if mode == "live":
        liveness = models.liveness.check(image, face)
        result["liveness"] = {"passed": liveness.passed, "score": round(liveness.score, 4)}
    return result


def detect(models: Models, image: Image) -> list[Face]:
    height, width = image.shape[:2]
    scale = min(1.0, DETECT_SIDE / max(height, width))
    if scale == 1.0:
        return models.face_detector.detect(image)
    small = cv2.resize(image, (round(width * scale), round(height * scale)), interpolation=cv2.INTER_AREA)
    faces = []
    for face in models.face_detector.detect(small):
        x, y, w, h = face.box
        faces.append(
            Face(
                (x / scale, y / scale, w / scale, h / scale),
                tuple((px / scale, py / scale) for px, py in face.landmarks),
                face.score,
            )
        )
    return faces


class DuplicatesRequest(BaseModel):
    embeddings: list[str] = Field(min_length=1, max_length=20)
    exclude_employee_id: str | None = None
    threshold: float = Field(ge=0, le=1)


@router.post("/v1/duplicates")
def duplicates(request: Request, body: DuplicatesRequest) -> list[dict[str, Any]]:
    context: Context = request.app.state.context
    dim = models_of(context).face_embedder.dim
    vectors = []
    for value in body.embeddings:
        try:
            vector = np.frombuffer(base64.b64decode(value, validate=True), dtype="<f4")
        except (binascii.Error, ValueError) as error:
            raise ApiError(400, "BAD_REQUEST", "An embedding is not base64 float32") from error
        if vector.shape[0] != dim:
            raise ApiError(400, "BAD_REQUEST", f"An embedding does not have {dim} values")
        vectors.append(vector)
    queries = normalise(np.stack(vectors).astype(np.float32))
    found = context.gallery.current().lookalikes(queries, body.exclude_employee_id, body.threshold)
    return [{"employee_id": m.employee_id, "score": m.score} for m in found]

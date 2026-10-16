"""The face gallery: every matchable template, decrypted in memory only.

Built from ``v_worker_gallery`` (active templates of enabled, consenting employees with an enrolled profile). A
template of another embedder (``model_name`` or ``dim``) is refused: changing the face model makes old templates
unusable, and the count of refused templates is reported in the heartbeat as "re-enrolment required". A new gallery is
built off-thread and swapped in atomically.
"""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from krz_worker.crypto import CryptoError, KeyRing
from krz_worker.db import GalleryRow
from krz_worker.models.base import Vectors
from krz_worker.models.face_embedder import normalise
from krz_worker.recognition.matcher import Match, top_employees

LOG = logging.getLogger(__name__)


@dataclass(frozen=True)
class GallerySnapshot:
    matrix: Vectors
    owners: NDArray[np.int64]
    employee_ids: list[str]
    #: Templates of another model or that could not be decrypted (re-enrolment required).
    refused: int

    @property
    def templates(self) -> int:
        return int(self.matrix.shape[0])

    def match(self, queries: Vectors, k: int = 3) -> list[Match]:
        return top_employees(self.matrix, self.owners, self.employee_ids, queries, k)

    def lookalikes(self, queries: Vectors, exclude: str | None, threshold: float) -> list[Match]:
        """Every other employee at or above the threshold, most similar first (the enrolment duplicate check)."""
        found = top_employees(self.matrix, self.owners, self.employee_ids, queries, k=len(self.employee_ids))
        return [m for m in found if m.employee_id != exclude and m.score >= threshold]


EMPTY = GallerySnapshot(np.zeros((0, 128), dtype=np.float32), np.zeros(0, dtype=np.int64), [], 0)


def build(rows: list[GalleryRow], ring: KeyRing, model_name: str, dim: int) -> GallerySnapshot:
    vectors: list[Vectors] = []
    owners: list[int] = []
    ids: dict[str, int] = {}
    refused = 0
    for row in rows:
        if row.model_name != model_name or row.dim != dim:
            refused += 1
            continue
        try:
            raw = ring.decrypt(row.embedding_enc)
        except CryptoError:
            LOG.warning("Template %s could not be decrypted", row.template_id, extra={"employee_id": row.employee_id})
            refused += 1
            continue
        vector = np.frombuffer(raw, dtype="<f4")
        if vector.shape[0] != dim:
            refused += 1
            continue
        vectors.append(vector)
        owners.append(ids.setdefault(row.employee_id, len(ids)))
    if refused:
        LOG.warning("%d templates were refused (another face model or unreadable): re-enrolment required", refused)
    if not vectors:
        return GallerySnapshot(np.zeros((0, dim), dtype=np.float32), np.zeros(0, dtype=np.int64), [], refused)
    return GallerySnapshot(
        normalise(np.stack(vectors).astype(np.float32)), np.array(owners, dtype=np.int64), list(ids), refused
    )


class Gallery:
    """The current snapshot; readers never see a half-built one."""

    def __init__(self) -> None:
        self._snapshot = EMPTY
        self._lock = threading.Lock()

    def current(self) -> GallerySnapshot:
        with self._lock:
            return self._snapshot

    def swap(self, snapshot: GallerySnapshot) -> None:
        with self._lock:
            self._snapshot = snapshot
        LOG.info(
            "Gallery loaded: %d templates of %d employees (%d refused)",
            snapshot.templates,
            len(snapshot.employee_ids),
            snapshot.refused,
        )

"""Cosine matching on L2-normalised vectors, ranked by employee: an employee's score is the best over their
templates (or body embeddings)."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from krz_worker.models.base import Vectors


@dataclass(frozen=True)
class Match:
    employee_id: str
    score: float


def top_employees(
    matrix: Vectors, owners: NDArray[np.int64], employee_ids: list[str], queries: Vectors, k: int = 3
) -> list[Match]:
    """The ``k`` best employees for the queries (rows of ``queries``; the best query counts), highest score first.

    ``owners[i]`` is the index in ``employee_ids`` of the employee whose vector is ``matrix[i]``.
    """
    if matrix.shape[0] == 0 or queries.shape[0] == 0:
        return []
    similarities = (matrix @ queries.T).max(axis=1)
    best = np.full(len(employee_ids), -1.0, dtype=np.float32)
    np.maximum.at(best, owners, similarities)
    order = np.argsort(-best)[:k]
    return [Match(employee_ids[i], round(float(best[i]), 4)) for i in order if best[i] > -1.0]

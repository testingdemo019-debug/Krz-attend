"""The same-day Re-ID gallery in Redis (Redis contract §5), owned by the worker.

- ``krz:reid:emb:<employee_id>``: up to 10 body embeddings (float16, base64) from that employee's identified IN
  crossings today; 20 h TTL, refreshed on each IN.
- ``krz:reid:crossing:<crossing_uid>``: the body embeddings of an undecided crossing, kept 20 h for a review to bind.
- ``krz:reid:index``: who has a gallery today (the worker's ACL has no SCAN); entries whose list expired are dropped.

Nothing here is copied to PostgreSQL; body features expire within 20 hours (the consent notice says so). The ACL has
no LREM or MULTI, so an unbind rewrites the employee's list.
"""

from __future__ import annotations

import base64
import logging
from typing import Any

import numpy as np

from krz_worker.models.base import Vectors
from krz_worker.recognition.matcher import Match

LOG = logging.getLogger(__name__)

TTL_SECONDS = 20 * 3600
PER_EMPLOYEE = 10
EMBEDDINGS = "krz:reid:emb:"
CROSSINGS = "krz:reid:crossing:"
INDEX = "krz:reid:index"


def encode(vector: Vectors) -> bytes:
    return base64.b64encode(np.asarray(vector, dtype="<f2").tobytes())


def decode(value: bytes) -> Vectors:
    vector = np.frombuffer(base64.b64decode(value), dtype="<f2").astype(np.float32)
    norm = float(np.linalg.norm(vector))
    result: Vectors = vector / norm if norm > 0 else vector
    return result


class ReIdGallery:
    def __init__(self, client: Any) -> None:
        self._redis = client

    def add(self, employee_id: str, bodies: Vectors) -> None:
        """After an AUTO IN: the track's body embeddings join the employee's gallery for today."""
        if bodies.shape[0] == 0:
            return
        key = EMBEDDINGS + employee_id
        pipe = self._redis.pipeline(transaction=False)
        pipe.lpush(key, *[encode(v) for v in bodies])
        pipe.ltrim(key, 0, PER_EMPLOYEE - 1)
        pipe.expire(key, TTL_SECONDS)
        pipe.hset(INDEX, employee_id, "1")
        pipe.expire(INDEX, TTL_SECONDS)
        pipe.execute()

    def hold(self, crossing_uid: str, bodies: Vectors) -> None:
        """An undecided crossing's body embeddings, kept for a later review decision (``reid.bind``)."""
        if bodies.shape[0] == 0:
            return
        key = CROSSINGS + crossing_uid
        pipe = self._redis.pipeline(transaction=False)
        pipe.lpush(key, *[encode(v) for v in bodies])
        pipe.expire(key, TTL_SECONDS)
        pipe.execute()

    def match(self, bodies: Vectors, k: int = 3) -> list[Match]:
        """The employees whose same-day bodies best match, highest first (best pair of embeddings counts)."""
        if bodies.shape[0] == 0:
            return []
        employees = [e.decode() for e in self._redis.hgetall(INDEX)]
        if not employees:
            return []
        pipe = self._redis.pipeline(transaction=False)
        for employee in employees:
            pipe.lrange(EMBEDDINGS + employee, 0, -1)
        lists = pipe.execute()
        expired = [e for e, values in zip(employees, lists, strict=True) if not values]
        if expired:
            self._redis.hdel(INDEX, *expired)
        scores = []
        for employee, values in zip(employees, lists, strict=True):
            if values:
                gallery = np.stack([decode(v) for v in values])
                scores.append(Match(employee, round(float((gallery @ bodies.T).max()), 4)))
        return sorted(scores, key=lambda m: m.score, reverse=True)[:k]

    def bind(self, crossing_uid: str, employee_id: str) -> None:
        """A review resolved the crossing to the employee: its bodies join that employee's gallery."""
        values = self._redis.lrange(CROSSINGS + crossing_uid, 0, -1)
        if values:
            self.add(employee_id, np.stack([decode(v) for v in values]))

    def unbind(self, crossing_uid: str, employee_id: str) -> None:
        """An undo or reassignment: the crossing's bodies leave the employee's gallery again."""
        bound = set(self._redis.lrange(CROSSINGS + crossing_uid, 0, -1))
        if not bound:
            return
        key = EMBEDDINGS + employee_id
        kept = [v for v in self._redis.lrange(key, 0, -1) if v not in bound]
        pipe = self._redis.pipeline(transaction=False)
        pipe.delete(key)
        if kept:
            pipe.lpush(key, *reversed(kept))
            pipe.expire(key, TTL_SECONDS)
        pipe.execute()

"""Unknown faces of a day, grouped (Redis contract §6), so one unknown visitor becomes one review item.

``krz:unknown:<IST day>`` maps a label to its centroid (float16, base64); ``krz:unknown:seq:<IST day>`` counts the
labels A1...A9, B1... A face joins the most similar cluster at cosine >= 0.50 (the centroid moves towards it),
otherwise it starts a new one. Both keys live 26 h.
"""

from __future__ import annotations

import datetime as dt
from typing import Any

import numpy as np

from krz_worker.models.base import Vectors
from krz_worker.models.face_embedder import normalise
from krz_worker.recognition.reid_gallery import decode, encode

JOIN = 0.50
TTL_SECONDS = 26 * 3600


def label(number: int) -> str:
    """The n-th label of a day (1-based): A1...A9, B1...B9, ... (the prototype's nextUnknownLabel)."""
    index = number - 1
    return chr(ord("A") + index // 9) + str(index % 9 + 1)


class UnknownClusters:
    def __init__(self, client: Any) -> None:
        self._redis = client

    def assign(self, day: dt.date, embedding: Vectors) -> str:
        """The cluster of the face for the crossing message: ``2026-10-12:A3``."""
        key = f"krz:unknown:{day.isoformat()}"
        query = normalise(np.asarray(embedding, dtype=np.float32).reshape(1, -1))[0]
        best_label, best_score, best_centroid = None, JOIN, None
        for name, value in self._redis.hgetall(key).items():
            centroid = decode(value)
            score = float(centroid @ query)
            if score >= best_score:
                best_label, best_score, best_centroid = name.decode(), score, centroid
        if best_label is None or best_centroid is None:
            # INCR exactly: redis-py's incr() sends INCRBY, which the worker's Redis ACL does not allow.
            best_label = label(int(self._redis.execute_command("INCR", f"krz:unknown:seq:{day.isoformat()}")))
            centroid = query
        else:
            centroid = normalise((best_centroid + query).reshape(1, -1))[0]
        pipe = self._redis.pipeline(transaction=False)
        pipe.hset(key, best_label, encode(centroid))
        pipe.expire(key, TTL_SECONDS)
        pipe.expire(f"krz:unknown:seq:{day.isoformat()}", TTL_SECONDS)
        pipe.execute()
        return f"{day.isoformat()}:{best_label}"
